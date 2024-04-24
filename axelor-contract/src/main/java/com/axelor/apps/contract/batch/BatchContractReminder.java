package com.axelor.apps.contract.batch;

import com.axelor.apps.account.service.batch.BatchStrategy;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.contract.db.Contract;
import com.axelor.apps.contract.db.ContractBatch;
import com.axelor.apps.contract.db.ContractVersion;
import com.axelor.apps.contract.db.repo.ContractBatchRepository;
import com.axelor.apps.contract.db.repo.ContractRepository;
import com.axelor.db.JPA;
import com.axelor.db.Model;
import com.axelor.db.Query;
import com.axelor.i18n.I18n;
import com.axelor.message.db.EmailAddress;
import com.axelor.message.db.Message;
import com.axelor.message.db.Template;
import com.axelor.message.db.repo.TemplateRepository;
import com.axelor.message.service.MessageServiceImpl;
import com.axelor.message.service.TemplateMessageServiceImpl;
import com.axelor.studio.db.AppContract;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.MessagingException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

public class BatchContractReminder extends BatchStrategy {
    protected Logger logger = LoggerFactory.getLogger(getClass());
    protected TemplateMessageServiceImpl templateMessageService;
    protected TemplateRepository templateRepository;
    protected MessageServiceImpl messageService;
    protected ContractRepository contractRepository;
    protected ContractBatchRepository contractBatchRepository;
    protected AppBaseService appBaseService;


    @Inject
    public BatchContractReminder(
            TemplateMessageServiceImpl templateMessageService,
            TemplateRepository templateRepository,
            MessageServiceImpl messageService,
            ContractRepository contractRepository,
            ContractBatchRepository contractBatchRepository,
            AppBaseService appBaseService
    ) {
        this.templateMessageService = templateMessageService;
        this.templateRepository = templateRepository;
        this.messageService = messageService;
        this.contractRepository = contractRepository;
        this.contractBatchRepository = contractBatchRepository;
        this.appBaseService = appBaseService;
    }

    protected void sendReminderMail(Contract contract) {
        try {
            AppContract appContract = (AppContract) appBaseService.getApp("contract");
            Template mailTemplate = appContract.getMailTemplate();
            Message message = templateMessageService.generateMessage((Model) contract, mailTemplate);
            messageService.sendByEmail(message);
        } catch (ClassNotFoundException | MessagingException e) {
            logger.error(e.getMessage());
        }
    }

    protected  List<Contract> getContracts(int offset) {
        return JPA.all(Contract.class)
                .filter("self.statusSelect = :activeContract")
                .bind("activeContract", ContractRepository.ACTIVE_CONTRACT)
                .fetch(FETCH_LIMIT, offset);
    }

    protected boolean remindCondition(LocalDate supposedEndDate, LocalDate batchEndDate) {
        return supposedEndDate.isBefore(batchEndDate) ||
                supposedEndDate.isEqual(batchEndDate);
    }

    protected LocalDate computeBatchEndDate(int duration, int durationType) {
        var batchEndDate = LocalDate.now();
        switch (durationType) {
            case ContractBatchRepository.DAYS:
                batchEndDate = batchEndDate.plusDays(duration);
                break;
            case ContractBatchRepository.WEEKS:
                batchEndDate = batchEndDate.plusWeeks(duration);
                break;
            case ContractBatchRepository.MONTHS:
                batchEndDate = batchEndDate.plusMonths(duration);
                break;
            default:
                batchEndDate = null;
                break;
        }
        return batchEndDate;
    }

    protected boolean needsToBeReminded(Contract contract, int duration, int durationType) {
        var supposedEndDate = contract.getCurrentContractVersion().getSupposedEndDate();
        var batchEndDate = computeBatchEndDate(duration, durationType);
        if (supposedEndDate == null || batchEndDate == null) {
            return false;
        }
        return remindCondition(supposedEndDate, batchEndDate);
    }

    @Override
    protected void process() throws SQLException {
        ContractBatch contractBatch = batch.getContractBatch();
        var duration = contractBatch.getDuration();
        var durationType = contractBatch.getTypeSelect();
        int offset = 0;
        List<Contract> contracts = getContracts(offset);
        for (; !contracts.isEmpty(); contracts = getContracts(offset)) {
            for (Contract contract : getContracts(offset)) {
                ++offset;
                try {
                    if (needsToBeReminded(contract, duration, durationType)) {
                        sendReminderMail(contract);
                        incrementDone();
                    }
                } catch (Exception e) {
                    incrementAnomaly();
                    TraceBackService.trace(e, "Contract reminding batch", batch.getId());
                }
            }
            JPA.clear();
        }
        String anomalies = I18n.get("anomaly", "anomalies", batch.getAnomaly());
        String success = I18n.get("done", "done", batch.getDone());
        addComment(String.format("%d %s, %d %s.", batch.getDone(), success, batch.getAnomaly(), anomalies));
    }
}
