/**
 * Axelor Business Solutions
 *
 * Copyright (C) 2017 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.account.service;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import javax.xml.bind.JAXBException;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;

import java.time.ZonedDateTime;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.Reconcile;
import com.axelor.apps.account.db.Reimbursement;
import com.axelor.apps.account.db.repo.MoveLineRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.db.repo.ReimbursementRepository;
import com.axelor.apps.account.exception.IExceptionMessage;
import com.axelor.apps.account.service.app.AppAccountService;
import com.axelor.apps.account.service.app.AppAccountServiceImpl;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.account.service.move.MoveLineService;
import com.axelor.apps.account.service.move.MoveService;
import com.axelor.apps.account.xsd.pain_001_001_02.AccountIdentification3Choice;
import com.axelor.apps.account.xsd.pain_001_001_02.AmountType2Choice;
import com.axelor.apps.account.xsd.pain_001_001_02.BranchAndFinancialInstitutionIdentification3;
import com.axelor.apps.account.xsd.pain_001_001_02.CashAccount7;
import com.axelor.apps.account.xsd.pain_001_001_02.CreditTransferTransactionInformation1;
import com.axelor.apps.account.xsd.pain_001_001_02.CurrencyAndAmount;
import com.axelor.apps.account.xsd.pain_001_001_02.Document;
import com.axelor.apps.account.xsd.pain_001_001_02.FinancialInstitutionIdentification5Choice;
import com.axelor.apps.account.xsd.pain_001_001_02.GroupHeader1;
import com.axelor.apps.account.xsd.pain_001_001_02.Grouping1Code;
import com.axelor.apps.account.xsd.pain_001_001_02.ObjectFactory;
import com.axelor.apps.account.xsd.pain_001_001_02.Pain00100102;
import com.axelor.apps.account.xsd.pain_001_001_02.PartyIdentification8;
import com.axelor.apps.account.xsd.pain_001_001_02.PaymentIdentification1;
import com.axelor.apps.account.xsd.pain_001_001_02.PaymentInstructionInformation1;
import com.axelor.apps.account.xsd.pain_001_001_02.PaymentMethod3Code;
import com.axelor.apps.account.xsd.pain_001_001_02.PaymentTypeInformation1;
import com.axelor.apps.account.xsd.pain_001_001_02.RemittanceInformation1;
import com.axelor.apps.account.xsd.pain_001_001_02.ServiceLevel1Code;
import com.axelor.apps.account.xsd.pain_001_001_02.ServiceLevel2Choice;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.IAdministration;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.service.PartnerService;
import com.axelor.apps.base.service.administration.SequenceService;
import com.axelor.apps.tool.xml.Marschaller;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.IException;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

public class ReimbursementExportService {

	private final Logger log = LoggerFactory.getLogger( MethodHandles.lookup().lookupClass() );

	protected MoveService moveService;
	protected MoveRepository moveRepo;
	protected MoveLineService moveLineService;
	protected ReconcileService reconcileService;
	protected SequenceService sequenceService;
	protected AccountBlockingService accountBlockingService;
	protected ReimbursementRepository reimbursementRepo;
	protected AccountConfigService accountConfigService;
	protected PartnerService partnerService;
	protected PartnerRepository partnerRepository;
	protected LocalDate today;

	@Inject
	public ReimbursementExportService(MoveService moveService, MoveRepository moveRepo, MoveLineService moveLineService, ReconcileService reconcileService,
			SequenceService sequenceService, AccountBlockingService accountBlockingService, ReimbursementRepository reimbursementRepo, AccountConfigService accountConfigService,
			PartnerService partnerService, AppAccountService appAccountService, PartnerRepository partnerRepository) {

		this.moveService = moveService;
		this.moveRepo = moveRepo;
		this.moveLineService = moveLineService;
		this.reconcileService = reconcileService;
		this.sequenceService = sequenceService;
		this.accountBlockingService = accountBlockingService;
		this.reimbursementRepo = reimbursementRepo;
		this.accountConfigService = accountConfigService;
		this.partnerService = partnerService;
		this.partnerRepository = partnerRepository;
		this.today = appAccountService.getTodayDate();
	}

	/**
	 *
	 * @param reimbursementExport
	 */
	public void fillMoveLineSet(Reimbursement reimbursement, List<MoveLine> moveLineList, BigDecimal total)  {

		log.debug("In fillMoveLineSet");
		log.debug("Nombre de trop-perçus trouvés : {}", moveLineList.size());

		for(MoveLine moveLine : moveLineList)  {
			// On passe les lignes d'écriture (trop perçu) à l'état 'en cours de remboursement'
			moveLine.setReimbursementStatusSelect(MoveLineRepository.REIMBURSEMENT_STATUS_REIMBURSING);
		}

		reimbursement.setMoveLineSet(new HashSet<MoveLine>());
		reimbursement.getMoveLineSet().addAll(moveLineList);

		log.debug("End fillMoveLineSet");
	}


	/**
	 *
	 * @param reimbursementExport
	 * @throws AxelorException
	 */
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public Reimbursement runCreateReimbursement(List<MoveLine> moveLineList, Company company, Partner partner) throws AxelorException  {

		log.debug("In runReimbursementProcess");

		BigDecimal total = this.getTotalAmountRemaining(moveLineList);

		AccountConfig accountConfig = company.getAccountConfig();

		// Seuil bas respecté et remboursement manuel autorisé
		if(total.compareTo(accountConfig.getLowerThresholdReimbursement()) > 0 )  {

			Reimbursement reimbursement = createReimbursement(partner, company);

			fillMoveLineSet(reimbursement, moveLineList, total);

			if(total.compareTo(accountConfig.getUpperThresholdReimbursement()) > 0 || reimbursement.getBankDetails() == null)  {
			// Seuil haut dépassé
				reimbursement.setStatusSelect(ReimbursementRepository.STATUS_TO_VALIDATE);
			}
			else  {
				reimbursement.setStatusSelect(ReimbursementRepository.STATUS_VALIDATED);
			}

			reimbursement = reimbursementRepo.save(reimbursement);
			return reimbursement;
		}

		log.debug("End runReimbursementProcess");
		return null;
	}


	/**
	 * Fonction permettant de calculer le montant total restant à payer / à lettrer
	 * @param movelineList
	 * 				Une liste de ligne d'écriture
	 * @return
	 * 				Le montant total restant à payer / à lettrer
	 */
	public BigDecimal getTotalAmountRemaining(List<MoveLine> moveLineList)  {
		BigDecimal total = BigDecimal.ZERO;
		for(MoveLine moveLine : moveLineList)  {
			total=total.add(moveLine.getAmountRemaining());
		}

		log.debug("Total Amount Remaining : {}",total);

		return total;
	}


	/**
	 * Methode permettant de créer l'écriture de remboursement
	 * @param reimbursementExport
	 * 				Un objet d'export des prélèvements
	 * @throws AxelorException
	 */
	public void createReimbursementMove(Reimbursement reimbursement, Company company) throws AxelorException  {
		reimbursement = reimbursementRepo.find(reimbursement.getId());

		Partner partner = null;
		Move newMove = null;
		boolean first = true;

		AccountConfig accountConfig = company.getAccountConfig();

		if(reimbursement.getMoveLineSet() != null && !reimbursement.getMoveLineSet().isEmpty())  {
			int seq = 1;
			for(MoveLine moveLine : reimbursement.getMoveLineSet())  {
				BigDecimal amountRemaining = moveLine.getAmountRemaining();
				if(amountRemaining.compareTo(BigDecimal.ZERO) > 0)  {
					partner = moveLine.getPartner();

					// On passe les lignes d'écriture (trop perçu) à l'état 'remboursé'
					moveLine.setReimbursementStatusSelect(MoveLineRepository.REIMBURSEMENT_STATUS_REIMBURSED);

					if(first)  {
						newMove = moveService.getMoveCreateService().createMove(accountConfig.getReimbursementJournal(), company, null, partner, null, MoveRepository.TECHNICAL_ORIGIN_AUTOMATIC);
						first = false;
					}
					// Création d'une ligne au débit
					MoveLine newDebitMoveLine = moveLineService.createMoveLine(newMove , partner, moveLine.getAccount(), amountRemaining, true, today, seq, null);
					newMove.getMoveLineList().add(newDebitMoveLine);
					if(reimbursement.getDescription() != null && !reimbursement.getDescription().isEmpty())  {
						newDebitMoveLine.setDescription(reimbursement.getDescription());
					}

					seq++;

					//Création de la réconciliation
					Reconcile reconcile = reconcileService.createReconcile(newDebitMoveLine, moveLine, amountRemaining, false);
					reconcileService.confirmReconcile(reconcile, true);
				}
			}
			// Création de la ligne au crédit
			MoveLine newCreditMoveLine = moveLineService.createMoveLine(newMove, partner, accountConfig.getReimbursementAccount(), reimbursement.getAmountReimbursed(), false, today, seq, null);

			newMove.getMoveLineList().add(newCreditMoveLine);
			if(reimbursement.getDescription() != null && !reimbursement.getDescription().isEmpty())  {
				newCreditMoveLine.setDescription(reimbursement.getDescription());
			}
			moveService.getMoveValidateService().validateMove(newMove);
			moveRepo.save(newMove);
		}
	}


	/**
	 * Procédure permettant de tester la présence des champs et des séquences nécessaire aux remboursements.
	 *
	 * @param company
	 * 			Une société
	 * @throws AxelorException
	 */
	public void testCompanyField(Company company) throws AxelorException  {

		AccountConfig accountConfig = accountConfigService.getAccountConfig(company);

		accountConfigService.getReimbursementAccount(accountConfig);
		accountConfigService.getReimbursementJournal(accountConfig);
		accountConfigService.getReimbursementExportFolderPath(accountConfig);

		if(!sequenceService.hasSequence(IAdministration.REIMBOURSEMENT, company)) {
			throw new AxelorException(IException.CONFIGURATION_ERROR, I18n.get(IExceptionMessage.REIMBURSEMENT_1), AppAccountServiceImpl.EXCEPTION,company.getName());
		}

	}


	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void reimburse(Reimbursement reimbursement, Company company) throws AxelorException  {
		reimbursement.setAmountReimbursed(reimbursement.getAmountToReimburse());
		this.createReimbursementMove(reimbursement, company);
		reimbursement.setStatusSelect(ReimbursementRepository.STATUS_REIMBURSED);
		reimbursementRepo.save(reimbursement);
	}


	/**
	 * Méthode permettant de créer un remboursement
	 * @param partner
	 * 				Un tiers
	 * @param company
	 * 				Une société
	 * @param reimbursementExport
	 * 				Un export des remboursement
	 * @return
	 * 			  	Le remboursmeent créé
	 * @throws AxelorException
	 */
	public Reimbursement createReimbursement(Partner partner, Company  company) throws AxelorException   {
		Reimbursement reimbursement = new Reimbursement();
		reimbursement.setPartner(partner);
		reimbursement.setCompany(company);

		BankDetails bankDetails = partnerService.getDefaultBankDetails(partner);

		reimbursement.setBankDetails(bankDetails);

		reimbursement.setRef(sequenceService.getSequenceNumber(IAdministration.REIMBOURSEMENT, company));

		return reimbursement;
	}


	/**
	 * Le tiers peux t-il être remboursé ?
	 * Si le tiers est bloqué en remboursement et que la date de fin de blocage n'est pas passée alors on ne peut pas rembourser.
	 *
	 * @return
	 */
	public boolean canBeReimbursed(Partner partner, Company company){
		return !accountBlockingService.isReimbursementBlocking(partner, company);
	}



	/**
	 * Procédure permettant de mettre à jour la liste des RIBs du tiers
	 * @param reimbursement
	 * 				Un remboursement
	 */
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void updatePartnerCurrentRIB(Reimbursement reimbursement)  {
		BankDetails bankDetails = reimbursement.getBankDetails();
		Partner partner = reimbursement.getPartner();
		BankDetails defaultBankDetails = partnerService.getDefaultBankDetails(partner);
		
		if(partner != null && bankDetails != null && !bankDetails.equals(defaultBankDetails))  {
			bankDetails.setIsDefault(true);
			defaultBankDetails.setIsDefault(false);
			partner.addBankDetailsListItem(bankDetails);
			
			partnerRepository.save(partner);
		}
	}


	/**
	 * Méthode permettant de créer un fichier xml de virement au format SEPA
	 * @param export
	 * @param datetime
	 * @param reimbursementList
	 * @throws AxelorException
	 * @throws DatatypeConfigurationException
	 * @throws JAXBException
	 * @throws IOException
	 */
	public void exportSepa(Company company, ZonedDateTime datetime, List<Reimbursement> reimbursementList, BankDetails bankDetails) throws AxelorException, DatatypeConfigurationException, JAXBException, IOException {

		String exportFolderPath = accountConfigService.getReimbursementExportFolderPath(accountConfigService.getAccountConfig(company));

		if (exportFolderPath == null) {
			throw new AxelorException(IException.MISSING_FIELD, I18n.get(IExceptionMessage.REIMBURSEMENT_2), company.getName());
		}


		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
		DatatypeFactory datatypeFactory = DatatypeFactory.newInstance();
		Date date = Date.from(datetime.toInstant());
		BigDecimal ctrlSum = BigDecimal.ZERO;
		int nbOfTxs = 0;

	/**
	 * Création du documemnt XML en mémoire
	 */

		ObjectFactory factory = new ObjectFactory();

	// Débit

		// Paiement
		ServiceLevel2Choice svcLvl = factory.createServiceLevel2Choice();
		svcLvl.setCd(ServiceLevel1Code.SEPA);

		PaymentTypeInformation1 pmtTpInf = factory.createPaymentTypeInformation1();
		pmtTpInf.setSvcLvl(svcLvl);

		// Payeur
		PartyIdentification8 dbtr = factory.createPartyIdentification8();
		dbtr.setNm(bankDetails.getOwnerName());

		// IBAN
		AccountIdentification3Choice iban = factory.createAccountIdentification3Choice();
		iban.setIBAN(bankDetails.getIban());

		CashAccount7 dbtrAcct = factory.createCashAccount7();
		dbtrAcct.setId(iban);

		// BIC
		FinancialInstitutionIdentification5Choice finInstnId = factory.createFinancialInstitutionIdentification5Choice();
		finInstnId.setBIC(bankDetails.getBank().getCode());

		BranchAndFinancialInstitutionIdentification3 dbtrAgt = factory.createBranchAndFinancialInstitutionIdentification3();
		dbtrAgt.setFinInstnId(finInstnId);

	// Lot

		PaymentInstructionInformation1 pmtInf = factory.createPaymentInstructionInformation1();
		pmtInf.setPmtMtd(PaymentMethod3Code.TRF);
		pmtInf.setPmtTpInf(pmtTpInf);
		pmtInf.setReqdExctnDt(datatypeFactory.newXMLGregorianCalendar(dateFormat.format(date)));
		pmtInf.setDbtr(dbtr);
		pmtInf.setDbtrAcct(dbtrAcct);
		pmtInf.setDbtrAgt(dbtrAgt);

	// Crédit
		CreditTransferTransactionInformation1 cdtTrfTxInf = null; PaymentIdentification1 pmtId = null;
		AmountType2Choice amt = null; CurrencyAndAmount instdAmt = null;
		PartyIdentification8 cbtr = null; CashAccount7 cbtrAcct = null;
		BranchAndFinancialInstitutionIdentification3 cbtrAgt = null;
		RemittanceInformation1 rmtInf = null;
		for (Reimbursement reimbursement : reimbursementList){

			reimbursement = reimbursementRepo.find(reimbursement.getId());

			nbOfTxs++;
			ctrlSum = ctrlSum.add(reimbursement.getAmountReimbursed());
			bankDetails = reimbursement.getBankDetails();

			// Paiement
			pmtId = factory.createPaymentIdentification1();
			pmtId.setEndToEndId(reimbursement.getRef());

			// Montant
			instdAmt = factory.createCurrencyAndAmount();
			instdAmt.setCcy("EUR");
			instdAmt.setValue(reimbursement.getAmountReimbursed());

			amt = factory.createAmountType2Choice();
			amt.setInstdAmt(instdAmt);

			// Débiteur
			cbtr = factory.createPartyIdentification8();
			cbtr.setNm(bankDetails.getOwnerName());

			// IBAN
			iban = factory.createAccountIdentification3Choice();
			iban.setIBAN(bankDetails.getIban());

			cbtrAcct = factory.createCashAccount7();
			cbtrAcct.setId(iban);

			// BIC
			finInstnId = factory.createFinancialInstitutionIdentification5Choice();
			finInstnId.setBIC(bankDetails.getBank().getCode());

			cbtrAgt = factory.createBranchAndFinancialInstitutionIdentification3();
			cbtrAgt.setFinInstnId(finInstnId);

			rmtInf = factory.createRemittanceInformation1();

			rmtInf.getUstrd().add(reimbursement.getDescription());

			// Transaction
			cdtTrfTxInf = factory.createCreditTransferTransactionInformation1();
			cdtTrfTxInf.setPmtId(pmtId);
			cdtTrfTxInf.setAmt(amt);
			cdtTrfTxInf.setCdtr(cbtr);
			cdtTrfTxInf.setCdtrAcct(cbtrAcct);
			cdtTrfTxInf.setCdtrAgt(cbtrAgt);
			cdtTrfTxInf.setRmtInf(rmtInf);

			pmtInf.getCdtTrfTxInf().add(cdtTrfTxInf);
		}

	// En-tête
		dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

		GroupHeader1 grpHdr = factory.createGroupHeader1();
		grpHdr.setCreDtTm(datatypeFactory.newXMLGregorianCalendar(dateFormat.format(date)));
		grpHdr.setNbOfTxs(Integer.toString(nbOfTxs));
		grpHdr.setCtrlSum(ctrlSum);
		grpHdr.setGrpg(Grouping1Code.MIXD);
		grpHdr.setInitgPty(dbtr);

	// Parent
		Pain00100102 pain00100102 = factory.createPain00100102();
		pain00100102.setGrpHdr(grpHdr);
		pain00100102.getPmtInf().add(pmtInf);

	// Document
		Document xml = factory.createDocument();
		xml.setPain00100102(pain00100102);

		/**
		 * Création du documemnt XML physique
		 */
		Marschaller.marschalFile(factory.createDocument(xml), "com.axelor.apps.xsd.sepa",
				exportFolderPath, String.format("%s.xml", dateFormat.format(date)));

	}


	/************************* Remboursement lors d'une facture fin de cycle *********************************/



	/**
	 * Procédure permettant de créer un remboursement si un trop perçu est généré à la facture fin de cycle
	 * @param partner
	 * 			Un tiers
	 * @param company
	 * 			Une société
	 * @param moveLine
	 * 			Un trop-perçu
	 * @throws AxelorException
	 */
	@SuppressWarnings("unchecked")
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void createReimbursementInvoice(Partner partner, Company company, List<? extends MoveLine> moveLineList) throws AxelorException  {

		BigDecimal total = this.getTotalAmountRemaining((List<MoveLine>) moveLineList);

		if(total.compareTo(BigDecimal.ZERO) > 0)  {

			this.testCompanyField(company);

			Reimbursement reimbursement = this.createReimbursement(partner, company);

			this.fillMoveLineSet(reimbursement, (List<MoveLine>) moveLineList, total);

			if(total.compareTo(company.getAccountConfig().getUpperThresholdReimbursement()) > 0 || reimbursement.getBankDetails() == null)  {
			// Seuil haut dépassé
				reimbursement.setStatusSelect(ReimbursementRepository.STATUS_TO_VALIDATE);
			}
			else  {
			// Seuil haut non dépassé
				reimbursement.setStatusSelect(ReimbursementRepository.STATUS_VALIDATED);
			}
			reimbursementRepo.save(reimbursement);
		}

	}


	/**
	 * Procédure permettant de créer un remboursement si un trop perçu est généré à la facture fin de cycle grand comptes
	 * @param invoice
	 * 			Une facture
	 * @throws AxelorException
	 */
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void createReimbursementInvoice(Invoice invoice) throws AxelorException  {
		Company company = invoice.getCompany();
		Partner partner = invoice.getPartner();
		MoveLineRepository moveLineRepo = Beans.get(MoveLineRepository.class);
		// récupération des trop-perçus du tiers
		List<? extends MoveLine> moveLineList = moveLineRepo.all().filter("self.account.useForPartnerBalance = 'true' AND self.fromSchedulePaymentOk = 'false' " +
				"AND self.move.statusSelect = ?1 AND self.amountRemaining > 0 AND self.credit > 0 AND self.partner = ?2 AND self.reimbursementStatusSelect = ?3 ",
				MoveRepository.STATUS_VALIDATED , partner, MoveLineRepository.REIMBURSEMENT_STATUS_NULL).fetch();

		this.createReimbursementInvoice(partner, company, moveLineList);

	}


}
