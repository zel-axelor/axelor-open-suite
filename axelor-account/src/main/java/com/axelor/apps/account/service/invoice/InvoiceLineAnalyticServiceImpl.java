package com.axelor.apps.account.service.invoice;

import com.axelor.apps.account.db.AnalyticAccount;
import com.axelor.apps.account.db.AnalyticDistributionTemplate;
import com.axelor.apps.account.db.AnalyticMoveLine;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.repo.AccountAnalyticRulesRepository;
import com.axelor.apps.account.db.repo.AccountConfigRepository;
import com.axelor.apps.account.db.repo.AnalyticAccountRepository;
import com.axelor.apps.account.db.repo.AnalyticMoveLineRepository;
import com.axelor.apps.account.service.analytic.AnalyticMoveLineService;
import com.axelor.apps.account.service.analytic.AnalyticToolService;
import com.axelor.apps.account.service.app.AppAccountService;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.tool.service.ListToolService;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.exception.AxelorException;
import com.google.common.base.MoreObjects;
import com.google.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class InvoiceLineAnalyticServiceImpl implements InvoiceLineAnalyticService {

  protected AnalyticAccountRepository analyticAccountRepository;
  protected AccountAnalyticRulesRepository accountAnalyticRulesRepository;
  protected AnalyticMoveLineService analyticMoveLineService;
  protected AnalyticToolService analyticToolService;
  protected AccountConfigService accountConfigService;
  protected ListToolService listToolService;
  protected AppAccountService appAccountService;

  @Inject
  public InvoiceLineAnalyticServiceImpl(
      AnalyticAccountRepository analyticAccountRepository,
      AccountAnalyticRulesRepository accountAnalyticRulesRepository,
      AnalyticMoveLineService analyticMoveLineService,
      AnalyticToolService analyticToolService,
      AccountConfigService accountConfigService,
      ListToolService listToolService,
      AppAccountService appAccountService) {
    this.analyticAccountRepository = analyticAccountRepository;
    this.accountAnalyticRulesRepository = accountAnalyticRulesRepository;
    this.analyticMoveLineService = analyticMoveLineService;
    this.analyticToolService = analyticToolService;
    this.accountConfigService = accountConfigService;
    this.listToolService = listToolService;
    this.appAccountService = appAccountService;
  }

  @Override
  public List<AnalyticMoveLine> getAndComputeAnalyticDistribution(
      InvoiceLine invoiceLine, Invoice invoice) throws AxelorException {
    if (accountConfigService
            .getAccountConfig(invoice.getCompany())
            .getAnalyticDistributionTypeSelect()
        == AccountConfigRepository.DISTRIBUTION_TYPE_FREE) {
      return MoreObjects.firstNonNull(invoiceLine.getAnalyticMoveLineList(), new ArrayList<>());
    }

    AnalyticDistributionTemplate analyticDistributionTemplate =
        analyticMoveLineService.getAnalyticDistributionTemplate(
            invoice.getPartner(), invoiceLine.getProduct(), invoice.getCompany());

    invoiceLine.setAnalyticDistributionTemplate(analyticDistributionTemplate);

    if (invoiceLine.getAnalyticMoveLineList() != null) {
      invoiceLine.getAnalyticMoveLineList().clear();
    }
    return this.computeAnalyticDistribution(invoiceLine);
  }

  @Override
  public List<AnalyticMoveLine> computeAnalyticDistribution(InvoiceLine invoiceLine) {

    List<AnalyticMoveLine> analyticMoveLineList = invoiceLine.getAnalyticMoveLineList();
    LocalDate date =
        appAccountService.getTodayDate(
            invoiceLine.getInvoice() != null
                ? invoiceLine.getInvoice().getCompany()
                : Optional.ofNullable(AuthUtils.getUser())
                    .map(User::getActiveCompany)
                    .orElse(null));
    if ((analyticMoveLineList == null || analyticMoveLineList.isEmpty())) {
      return createAnalyticDistributionWithTemplate(invoiceLine);
    } else {
      if (invoiceLine.getAnalyticMoveLineList() != null) {
        for (AnalyticMoveLine analyticMoveLine : analyticMoveLineList) {
          analyticMoveLineService.updateAnalyticMoveLine(
              analyticMoveLine, invoiceLine.getCompanyExTaxTotal(), date);
        }
      }
      return analyticMoveLineList;
    }
  }

  @Override
  public List<AnalyticMoveLine> createAnalyticDistributionWithTemplate(InvoiceLine invoiceLine) {
    LocalDate date =
        appAccountService.getTodayDate(
            invoiceLine.getInvoice() != null
                ? invoiceLine.getInvoice().getCompany()
                : Optional.ofNullable(AuthUtils.getUser())
                    .map(User::getActiveCompany)
                    .orElse(null));
    List<AnalyticMoveLine> analyticMoveLineList =
        analyticMoveLineService.generateLines(
            invoiceLine.getAnalyticDistributionTemplate(),
            invoiceLine.getCompanyExTaxTotal(),
            AnalyticMoveLineRepository.STATUS_FORECAST_INVOICE,
            date);

    return analyticMoveLineList;
  }

  @Override
  public InvoiceLine selectDefaultDistributionTemplate(InvoiceLine invoiceLine)
      throws AxelorException {

    if (invoiceLine != null && invoiceLine.getAccount() != null) {
      if (invoiceLine.getAccount().getAnalyticDistributionAuthorized()
          && invoiceLine.getAccount().getAnalyticDistributionTemplate() != null
          && accountConfigService
                  .getAccountConfig(invoiceLine.getAccount().getCompany())
                  .getAnalyticDistributionTypeSelect()
              == AccountConfigRepository.DISTRIBUTION_TYPE_PRODUCT) {

        invoiceLine.setAnalyticDistributionTemplate(
            invoiceLine.getAccount().getAnalyticDistributionTemplate());
      }
    } else {
      invoiceLine.setAnalyticDistributionTemplate(null);
    }

    return invoiceLine;
  }

  @Override
  public InvoiceLine analyzeInvoiceLine(InvoiceLine invoiceLine, Invoice invoice)
      throws AxelorException {
    if (invoiceLine != null && invoice != null) {

      if (invoiceLine.getAnalyticMoveLineList() == null) {
        invoiceLine.setAnalyticMoveLineList(new ArrayList<>());
      } else {
        invoiceLine
            .getAnalyticMoveLineList()
            .forEach(analyticMoveLine -> analyticMoveLine.setInvoiceLine(null));
        invoiceLine.getAnalyticMoveLineList().clear();
      }

      AnalyticMoveLine analyticMoveLine = null;
      findAnalyticMoveLineWithAnalyticAccount(
          invoiceLine.getAxis1AnalyticAccount(), analyticMoveLine, invoiceLine, invoice);
      findAnalyticMoveLineWithAnalyticAccount(
          invoiceLine.getAxis2AnalyticAccount(), analyticMoveLine, invoiceLine, invoice);
      findAnalyticMoveLineWithAnalyticAccount(
          invoiceLine.getAxis3AnalyticAccount(), analyticMoveLine, invoiceLine, invoice);
      findAnalyticMoveLineWithAnalyticAccount(
          invoiceLine.getAxis4AnalyticAccount(), analyticMoveLine, invoiceLine, invoice);
      findAnalyticMoveLineWithAnalyticAccount(
          invoiceLine.getAxis5AnalyticAccount(), analyticMoveLine, invoiceLine, invoice);
    }
    return invoiceLine;
  }

  protected InvoiceLine findAnalyticMoveLineWithAnalyticAccount(
      AnalyticAccount analyticAccount,
      AnalyticMoveLine analyticMoveLine,
      InvoiceLine invoiceLine,
      Invoice invoice)
      throws AxelorException {
    if (analyticAccount != null) {
      analyticMoveLine =
          analyticMoveLineService.computeAnalyticMoveLine(
              invoiceLine, invoice, invoice.getCompany(), analyticAccount);
      invoiceLine.addAnalyticMoveLineListItem(analyticMoveLine);
    }
    return invoiceLine;
  }

  @Override
  public InvoiceLine clearAnalyticAccounting(InvoiceLine invoiceLine) {
    invoiceLine.setAxis1AnalyticAccount(null);
    invoiceLine.setAxis2AnalyticAccount(null);
    invoiceLine.setAxis3AnalyticAccount(null);
    invoiceLine.setAxis4AnalyticAccount(null);
    invoiceLine.setAxis5AnalyticAccount(null);
    invoiceLine
        .getAnalyticMoveLineList()
        .forEach(analyticMoveLine -> analyticMoveLine.setInvoiceLine(null));
    invoiceLine.getAnalyticMoveLineList().clear();
    return invoiceLine;
  }
}