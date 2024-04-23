package com.axelor.apps.contract.batch;

import com.axelor.apps.account.service.batch.BatchStrategy;
import com.axelor.apps.base.db.Batch;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.contract.db.Contract;
import com.axelor.apps.contract.db.ContractBatch;
import com.axelor.apps.contract.db.ContractVersion;
import com.axelor.apps.contract.db.repo.ContractBatchRepository;
import com.axelor.apps.contract.db.repo.ContractRepository;
import com.axelor.db.JPA;
import com.axelor.db.Model;
import com.axelor.db.Query;
import com.axelor.message.db.EmailAddress;
import com.axelor.message.db.Message;
import com.axelor.message.db.Template;
import com.axelor.message.db.repo.TemplateRepository;
import com.axelor.message.service.MessageServiceImpl;
import com.axelor.message.service.TemplateMessageServiceImpl;
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
    public static final String MAIL_TEMPLATE = "Contract batch mail template";

    @Inject
    public BatchContractReminder(
            TemplateMessageServiceImpl templateMessageService,
            TemplateRepository templateRepository,
            MessageServiceImpl messageService,
            ContractRepository contractRepository,
            ContractBatchRepository contractBatchRepository
    ) {
        this.templateMessageService = templateMessageService;
        this.templateRepository = templateRepository;
        this.messageService = messageService;
        this.contractRepository = contractRepository;
        this.contractBatchRepository = contractBatchRepository;
    }

    protected void sendReminderMail(Contract contract) {
        try {
            Template mailTemplate = templateRepository.findByName(MAIL_TEMPLATE);
            Message message = templateMessageService.generateMessage((Model) contract, mailTemplate);
            ContractVersion contractVersion = contract.getCurrentContractVersion();
            message.addToEmailAddressSetItem(new EmailAddress(contractVersion.getActivatedByUser().getEmail()));
            messageService.sendByEmail(message);
        } catch (ClassNotFoundException | MessagingException e) {
            logger.error(e.getMessage());
        }
    }

    protected  List<Contract> getContracts() {
        return Query.of(Contract.class)
                .filter("self.statusSelect = :activeContract")
                .bind("activeContract", ContractRepository.ACTIVE_CONTRACT)
                .fetch();
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

    protected boolean needsToBeReminded(Contract contract) {
        ContractBatch contractBatch = batch.getContractBatch();
        var duration = contractBatch.getDuration();
        var durationType = contractBatch.getTypeSelect();
        var supposedEndDate = contract.getCurrentContractVersion().getSupposedEndDate();
        var batchEndDate = computeBatchEndDate(duration, durationType);
        if (supposedEndDate == null || batchEndDate == null) {
            return false;
        }
        return remindCondition(supposedEndDate, batchEndDate);
    }

    @Override
    protected void process() throws SQLException {
        for (Contract contract : getContracts()) {
            try {
                if (needsToBeReminded(contract)) {
                    sendReminderMail(contract);
                    incrementDone();
                }
                // JPA.clear();
            } catch (Exception e) {
                incrementAnomaly();
                TraceBackService.trace(e, "Contract reminding batch", batch.getId());
            }
        }
    }
}
