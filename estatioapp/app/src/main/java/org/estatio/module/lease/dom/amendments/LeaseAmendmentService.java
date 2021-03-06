package org.estatio.module.lease.dom.amendments;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;

import com.google.common.collect.Lists;

import org.joda.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.isis.applib.annotation.DomainService;
import org.apache.isis.applib.annotation.NatureOfService;
import org.apache.isis.applib.services.factory.FactoryService;
import org.apache.isis.applib.services.message.MessageService;
import org.apache.isis.applib.services.registry.ServiceRegistry2;

import org.incode.module.base.dom.valuetypes.LocalDateInterval;

import org.estatio.module.agreement.dom.role.AgreementRoleType;
import org.estatio.module.agreement.dom.role.AgreementRoleTypeRepository;
import org.estatio.module.charge.dom.Charge;
import org.estatio.module.charge.dom.ChargeRepository;
import org.estatio.module.invoice.dom.InvoiceRunType;
import org.estatio.module.lease.contributions.Lease_calculate;
import org.estatio.module.lease.dom.InvoicingFrequency;
import org.estatio.module.lease.dom.Lease;
import org.estatio.module.lease.dom.LeaseAgreementRoleTypeEnum;
import org.estatio.module.lease.dom.LeaseItem;
import org.estatio.module.lease.dom.LeaseItemSource;
import org.estatio.module.lease.dom.LeaseItemSourceRepository;
import org.estatio.module.lease.dom.LeaseItemType;
import org.estatio.module.lease.dom.LeaseStatus;
import org.estatio.module.lease.dom.LeaseTerm;
import org.estatio.module.party.dom.Party;

@DomainService(
        nature = NatureOfService.DOMAIN,
        objectType = "org.estatio.module.lease.dom.amendments.LeaseAmendmentService"
)
public class LeaseAmendmentService {

    public static Logger LOG = LoggerFactory.getLogger(LeaseAmendmentService.class);

    public void apply(final LeaseAmendment leaseAmendment, final boolean preview) {

        if (leaseAmendment.getState()==LeaseAmendmentState.APPLIED) return;

        // Extra guard for supported types
        if (!Arrays.asList(
                LeaseAmendmentType.COVID_FRA_50_PERC,
                LeaseAmendmentType.COVID_FRA_100_PERC,
                LeaseAmendmentType.COVID_FRA_FREQ_CHANGE,
                LeaseAmendmentType.COVID_ITA_FREQ_CHANGE,
                LeaseAmendmentType.DEMO_TYPE).
                contains(leaseAmendment.getLeaseAmendmentType())
        ) {
            messageService.warnUser(String.format("Amendment type %s is not implemented (yet...)", leaseAmendment.getLeaseAmendmentType()));
            return;
        }

        final String message = String.format("Applying amendment %s for lease %s", leaseAmendment.getReference(), preview ? leaseAmendment.getLeasePreview().getReference() : leaseAmendment.getLease().getReference());
        LOG.info(message);
        if (!preview && leaseAmendment.getLeasePreview()!=null) {
            leaseAmendment.getLeasePreview().remove(message);
        }

        final Lease lease = preview ? leaseAmendment.getLeasePreview() : leaseAmendment.getLease();

        final LeaseAmendmentItemForDiscount leaseAmendmentItemForDiscount = Lists
                .newArrayList(leaseAmendment.getItems()).stream()
                .filter(lai -> lai.getClass().isAssignableFrom(LeaseAmendmentItemForDiscount.class))
                .map(LeaseAmendmentItemForDiscount.class::cast)
                .findFirst().orElse(null);
        // NOTE: we apply discount first, before frequency change, because it copies the original invoice frequnecy ...
        if (leaseAmendmentItemForDiscount!=null){
            final String message1 = String.format("Applying amendment item for discount for lease %s", preview ? leaseAmendment.getLeasePreview().getReference() : leaseAmendment.getLease().getReference());
            LOG.info(message1);
            applyDiscount(lease, leaseAmendmentItemForDiscount);
        }
        final LeaseAmendmentItemForFrequencyChange leaseAmendmentItemForFrequencyChange = Lists
                .newArrayList(leaseAmendment.getItems()).stream()
                .filter(lai -> lai.getClass().isAssignableFrom(LeaseAmendmentItemForFrequencyChange.class))
                .map(LeaseAmendmentItemForFrequencyChange.class::cast)
                .findFirst().orElse(null);
        if (leaseAmendmentItemForFrequencyChange!=null){
            final String message2 = String.format("Applying amendment item for frequency change for lease %s", preview ? leaseAmendment.getLeasePreview().getReference() : leaseAmendment.getLease().getReference());
            LOG.info(message2);
            applyFrequencyChange(lease, leaseAmendmentItemForFrequencyChange);
        }
        if (!preview) {
            final String message3 = String.format("Amendment %s for lease %s applied", leaseAmendment.getReference(), leaseAmendment.getLease().getReference());
            LOG.info(message3);
            leaseAmendment.setState(LeaseAmendmentState.APPLIED);
        }
        if (preview && leaseAmendment.getLeaseAmendmentType().getPreviewInvoicingStartDate()!=null && leaseAmendment.getLeaseAmendmentType().getPreviewInvoicingEndDate()!=null){
            List<LeaseItemType> typesForCalculation = Arrays.asList(LeaseItemType.RENT, LeaseItemType.RENT_DISCOUNT, LeaseItemType.RENT_DISCOUNT_FIXED, LeaseItemType.SERVICE_CHARGE, LeaseItemType.MARKETING, LeaseItemType.SERVICE_CHARGE_INDEXABLE, LeaseItemType.SERVICE_CHARGE_DISCOUNT_FIXED);
            factoryService.mixin(Lease_calculate.class, lease).exec(InvoiceRunType.NORMAL_RUN, typesForCalculation, leaseAmendment.getLeaseAmendmentType().getPreviewInvoicingStartDate(), leaseAmendment.getLeaseAmendmentType().getPreviewInvoicingStartDate(), leaseAmendment.getLeaseAmendmentType().getPreviewInvoicingEndDate().plusDays(1));
        }
    }

    void applyDiscount(
            final Lease lease,
            final LeaseAmendmentItemForDiscount leaseAmendmentItemForDiscount) {
        // find lease items included in discount
        final List<LeaseItem> itemsToIncludeForDiscount = Lists.newArrayList(lease.getItems()).stream()
                .filter(li -> LeaseAmendmentItem
                        .applicableToFromString(leaseAmendmentItemForDiscount.getApplicableTo())
                        .contains(li.getType()))
                .filter(li->li.getEffectiveInterval().overlaps(leaseAmendmentItemForDiscount.getInterval()))
                .collect(Collectors.toList());
        for (LeaseItem li : itemsToIncludeForDiscount){
            createDiscountItemAndTerms(li, leaseAmendmentItemForDiscount);
        }
    }

    void createDiscountItemAndTerms(LeaseItem sourceItem, final LeaseAmendmentItemForDiscount leaseAmendmentItemForDiscount){

        LocalDate startDateToUse = sourceItem.getStartDate()==null || sourceItem.getStartDate().isBefore(leaseAmendmentItemForDiscount.getStartDate()) ? leaseAmendmentItemForDiscount.getStartDate() : sourceItem.getStartDate();
        LocalDate endDateToUse = sourceItem.getEndDate()==null || sourceItem.getEndDate().isAfter(leaseAmendmentItemForDiscount.getEndDate()) ? leaseAmendmentItemForDiscount.getEndDate() : sourceItem.getEndDate();
        Lease lease = sourceItem.getLease();
        lease.verifyUntil(endDateToUse.plusDays(1));
        // prevent an item copy from being created when no terms for the discount period
        if (!sourceItem.hasTermsOverlapping(LocalDateInterval.including(leaseAmendmentItemForDiscount.getStartDate(), leaseAmendmentItemForDiscount.getEndDate()))) return;

        final Charge chargeFromAmendmentType = chargeRepository.findByReference(
                leaseAmendmentItemForDiscount.getLeaseAmendment().getLeaseAmendmentType()
                        .getChargeReferenceForDiscountItem());
        final Charge chargeToUse = chargeFromAmendmentType != null ? chargeFromAmendmentType : sourceItem.getCharge(); // This is a fallback. for testing f.i.
        final LeaseItem newDiscountItem = lease
                .newItem(sourceItem.getType(), sourceItem.getInvoicedBy(), chargeToUse, sourceItem.getInvoicingFrequency(), sourceItem.getPaymentMethod(), startDateToUse);
        newDiscountItem.setEndDate(endDateToUse);
        sourceItem.copyTerms(newDiscountItem.getStartDate(), newDiscountItem);
        newDiscountItem.negateAmountsAndApplyPercentageOnTerms(leaseAmendmentItemForDiscount.getDiscountPercentage());
        if (lease.getStatus()!=LeaseStatus.PREVIEW) {
            final String message = String.format("Item of type %s and charge %s for lease %s created with interval %s", newDiscountItem.getType(), newDiscountItem.getCharge().getReference(), lease.getReference(), newDiscountItem.getInterval().toString());
            LOG.info(message);
        }
    }

    void applyFrequencyChange(final Lease lease, final LeaseAmendmentItemForFrequencyChange leaseAmendmentItemForFrequencyChange){
        final List<LeaseItem> itemsToIncludeForFrequencyChange = Lists.newArrayList(lease.getItems()).stream()
                .filter(li -> LeaseAmendmentItem
                        .applicableToFromString(leaseAmendmentItemForFrequencyChange.getApplicableTo())
                        .contains(li.getType()))
                .filter(li->li.getInvoicingFrequency()==leaseAmendmentItemForFrequencyChange.getInvoicingFrequencyOnLease())
                .filter(li->li.getEffectiveInterval().overlaps(leaseAmendmentItemForFrequencyChange.getInterval()))
                .collect(Collectors.toList());
        for (LeaseItem originalItem : itemsToIncludeForFrequencyChange){
            switch (originalItem.getType()){
                case RENT:
                case RENT_DISCOUNT:
                case RENT_DISCOUNT_FIXED:
                case SERVICE_CHARGE:
                case SERVICE_CHARGE_INDEXABLE:
                case MARKETING:
                    final LeaseItem firstNewItem = closeOriginalAndOpenNewLeaseItem(
                            leaseAmendmentItemForFrequencyChange.getStartDate(),
                            originalItem,
                            leaseAmendmentItemForFrequencyChange.getAmendedInvoicingFrequency());
                    if (firstNewItem!=null){
                        closeOriginalAndOpenNewLeaseItem(leaseAmendmentItemForFrequencyChange.getEndDate().plusDays(1),
                                firstNewItem,
                                leaseAmendmentItemForFrequencyChange.getInvoicingFrequencyOnLease());
                    }
                    break;
                default:
                    final String warning = String
                            .format("Applying frequency change on lease %s for type %s is not (yet) supported",
                                    lease.getReference(), originalItem.getType());
                    messageService.warnUser(
                            warning);
                    LOG.error(warning);
                    break;
            }
        }
    }

    public Lease getLeasePreviewFor(final LeaseAmendment amendment){
        if (amendment.getItems().isEmpty()) return null;
        Lease leasePreview = getLeaseCopyForPreview(
                amendment.getLease(),
                amendment.getEffectiveStartDate(),
                amendment.getEffectiveEndDate(),
                amendment.getReference());
        amendment.setLeasePreview(leasePreview);
        apply(amendment, true);
        return leasePreview;
    }

    public Lease getLeaseCopyForPreview(final Lease originalLease, final LocalDate referenceDate, final LocalDate verificationDate, final String previewLeaseReference){

        originalLease.verifyUntil(verificationDate.plusDays(1));

        Lease leaseCopy = new Lease();
        leaseCopy.setStatus(LeaseStatus.PREVIEW);
        leaseCopy.setReference(previewLeaseReference);
        leaseCopy.setStartDate(originalLease.getStartDate());
        leaseCopy.setTenancyStartDate(originalLease.getTenancyStartDate());
        leaseCopy.setEndDate(originalLease.getEndDate());
        leaseCopy.setTenancyEndDate(originalLease.getTenancyEndDate());
        leaseCopy.setApplicationTenancyPath(originalLease.getApplicationTenancyPath());
        leaseCopy.setLeaseType(originalLease.getLeaseType());
        leaseCopy.setType(originalLease.getType());
        serviceRegistry2.injectServicesInto(leaseCopy);
        final Party tenant = originalLease.getSecondaryParty();
        if (tenant != null) {
            final AgreementRoleType artLandlord = agreementRoleTypeRepository.find(LeaseAgreementRoleTypeEnum.TENANT);
            leaseCopy.newRole(artLandlord, tenant, null, null);
        }
        final Party landlord = originalLease.getPrimaryParty();
        if (landlord != null) {
            final AgreementRoleType artLandlord = agreementRoleTypeRepository.find(LeaseAgreementRoleTypeEnum.LANDLORD);
            leaseCopy.newRole(artLandlord, landlord, null, null);
        }
        for (LeaseItem originalItem : originalLease.getItems()){
            if (referenceDate==null || (originalItem.getEffectiveInterval()!=null && originalItem.getEffectiveInterval().contains(referenceDate)) || (originalItem.getStartDate()!=null && originalItem.getStartDate().isAfter(referenceDate))) {
                LeaseItem newItem = leaseCopy.newItem(
                        originalItem.getType(),
                        originalItem.getInvoicedBy(),
                        originalItem.getCharge(),
                        originalItem.getInvoicingFrequency(),
                        originalItem.getPaymentMethod(),
                        originalItem.getStartDate()
                );
                newItem.setEndDate(originalItem.getEndDate());
                originalItem.copyTerms(referenceDate, newItem);
                //TODO: NOTE THAT WE COPY ALL ITEM TYPES BUT NOT THEIR SOURCES; this adds complications and may not be needed for reporting / forecasting
                // ANOTHER APPROACH would be to not copy these itemtypes at all ...
            }
        }
        return leaseCopy;
    }

    public LeaseItem closeOriginalAndOpenNewLeaseItem(final LocalDate startDateNewItem, final LeaseItem originalItem, final InvoicingFrequency invoicingFrequency){
        final Lease lease = originalItem.getLease();
        originalItem.verifyUntil(startDateNewItem.plusYears(1).plusDays(1)); // for some reason rent items were not verified correctly when verifying until startDateNewItem.plusDays(1)
        final LeaseTerm currentTerm = originalItem.currentTerm(startDateNewItem);
        if (currentTerm == null){
            LOG.warn(String.format("No current rent term found for lease %s, type %s, starting on %s", lease.getReference(), originalItem.getType(), startDateNewItem.toString()));
            return null;
        }
        final LeaseItem newItem = lease
                .newItem(originalItem.getType(), originalItem.getInvoicedBy(), originalItem.getCharge(), invoicingFrequency,
                        originalItem.getPaymentMethod(), startDateNewItem);
        newItem.setEndDate(originalItem.getEndDate());
        if (originalItem.getTax()!=null) newItem.setTax(originalItem.getTax());

        // NOTE: the order matters! We take endate of original
        originalItem.changeDates(originalItem.getStartDate(), startDateNewItem.minusDays(1));
        if (lease.getStatus()!=LeaseStatus.PREVIEW) {
            final String message = String.format("Item of type %s, charge %s and invoicing frequency %s for lease %s closed on date %s", originalItem.getType(), originalItem.getCharge().getReference(), originalItem.getInvoicingFrequency(), lease.getReference(), originalItem.getEndDate());
            LOG.info(message);
            final String message1 = String.format("Item of type %s, charge %s and invoicing frequency %s for lease %s created with interval %s", newItem.getType(), newItem.getCharge(), newItem.getInvoicingFrequency(), lease.getReference(), newItem.getInterval().toString());
            LOG.info(message1);
        }

        final LeaseTerm newTerm = newItem.newTerm(startDateNewItem, null);
        currentTerm.copyValuesTo(newTerm);
        // link new item to items that had old item as source
        final List<LeaseItemSource> sourceItems = leaseItemSourceRepository.findBySourceItem(originalItem);
        sourceItems.stream()
                .map(lis->lis.getItem()).forEach(li->li.newSourceItem(newItem));
        newItem.verifyUntil(startDateNewItem.plusMonths(2)); // TODO: this looks very random .... maybe derive from amendment item?
        return newItem;
    }

    public LeaseAmendmentType.Tuple<InvoicingFrequency, InvoicingFrequency> findInvoiceFrequencyTupleOnfirstFrequencyChangeCandidate(final LeaseAmendment amendment){
        return findInvoiceFrequencyTupleOnfirstFrequencyChangeCandidate(amendment.getLease(), amendment.getLeaseAmendmentType());
    }

    public LeaseAmendmentType.Tuple<InvoicingFrequency, InvoicingFrequency> findInvoiceFrequencyTupleOnfirstFrequencyChangeCandidate(final Lease lease, final LeaseAmendmentType leaseAmendmentType){
        final LeaseItem firstFrequencyChangeCandidateItem = Lists.newArrayList(lease.getItems()).stream()
                .filter(i->leaseAmendmentType.getFrequencyChangeAppliesTo()!=null)
                .filter(i-> leaseAmendmentType.getFrequencyChangeAppliesTo().contains(i.getType()))
                .filter(i->hasChangingFrequency(i, leaseAmendmentType))
                .filter(i->i.getEffectiveInterval()!=null)
                .filter(i->i.getEffectiveInterval().overlaps(
                        LocalDateInterval
                                .including(leaseAmendmentType.getFrequencyChangeStartDate(),leaseAmendmentType
                                        .getFrequencyChangeEndDate())))
                .findFirst().orElse(null);
        return firstFrequencyChangeCandidateItem!=null ? getTuple(firstFrequencyChangeCandidateItem, leaseAmendmentType) : null;
    }

    boolean hasChangingFrequency(final LeaseItem i, final LeaseAmendmentType leaseAmendmentType){
        final LeaseAmendmentType.Tuple<InvoicingFrequency, InvoicingFrequency> tuple = leaseAmendmentType.getFrequencyChanges()
                .stream()
                .filter(t -> t.oldFrequency == i.getInvoicingFrequency())
                .findFirst().orElse(null);
        return tuple != null;
    }

    LeaseAmendmentType.Tuple<InvoicingFrequency, InvoicingFrequency>  getTuple(final LeaseItem i, final LeaseAmendmentType leaseAmendmentType){
        return leaseAmendmentType.getFrequencyChanges()
                .stream()
                .filter(t -> t.oldFrequency == i.getInvoicingFrequency())
                .findFirst().orElse(null);
    }

    @Inject MessageService messageService;

    @Inject ServiceRegistry2 serviceRegistry2;

    @Inject AgreementRoleTypeRepository agreementRoleTypeRepository;

    @Inject ChargeRepository chargeRepository;

    @Inject LeaseItemSourceRepository leaseItemSourceRepository;

    @Inject FactoryService factoryService;

}
