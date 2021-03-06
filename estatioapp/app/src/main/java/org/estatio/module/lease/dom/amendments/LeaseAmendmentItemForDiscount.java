package org.estatio.module.lease.dom.amendments;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.jdo.annotations.Column;
import javax.jdo.annotations.InheritanceStrategy;
import javax.validation.constraints.Digits;

import com.google.common.collect.Lists;

import org.apache.isis.applib.annotation.Action;
import org.apache.isis.applib.annotation.ActionLayout;
import org.apache.isis.applib.annotation.Contributed;
import org.apache.isis.applib.annotation.SemanticsOf;

import org.estatio.module.lease.dom.Lease;
import org.estatio.module.lease.dom.LeaseItem;
import org.estatio.module.lease.dom.LeaseTerm;

import lombok.Getter;
import lombok.Setter;

@javax.jdo.annotations.PersistenceCapable(
        schema = "dbo" // Isis' ObjectSpecId inferred from @Discriminator
)
@javax.jdo.annotations.Inheritance(strategy = InheritanceStrategy.SUPERCLASS_TABLE)
@javax.jdo.annotations.Discriminator("amendments.LeaseAmendmentItemForDiscount")
public class LeaseAmendmentItemForDiscount extends LeaseAmendmentItem {

    @Column(allowsNull = "false", scale = 2)
    @Getter @Setter
    private BigDecimal discountPercentage;

    @Action(semantics = SemanticsOf.IDEMPOTENT)
    public LeaseAmendmentItemForDiscount changeDiscountPercentage(@Digits(integer = 3, fraction = 2) final BigDecimal newPercentage){
        setDiscountPercentage(newPercentage);
        return this;
    }

    public BigDecimal default0ChangeDiscountPercentage(){
        return getDiscountPercentage();
    }

    public String disableChangeDiscountPercentage(){
        final String warning = String.format("Amendment in state of %s cannot be changed", getLeaseAmendment().getState());
        return getLeaseAmendment().amendmentDataIsImmutable() ? warning : null;
    }

    @Action(semantics = SemanticsOf.SAFE)
    @ActionLayout(contributed = Contributed.AS_ASSOCIATION)
    public BigDecimal getCalculatedDiscountAmount(){
        final Lease leasePreview = getLeaseAmendment().getLeasePreview();
        if (leasePreview==null) return null;
        BigDecimal result = new BigDecimal("0.00");
        final List<LeaseItem> leaseItemsPossiblyInvolved = Lists.newArrayList(leasePreview.getItems()).stream()
                .filter(li -> getApplicableToAsList().contains(li.getType()))
                .collect(Collectors.toList());
        for (LeaseItem leaseItem : leaseItemsPossiblyInvolved){
            final String chargeReferenceForDiscountItemIfAny = getLeaseAmendment().getLeaseAmendmentType()
                    .getChargeReferenceForDiscountItem();
            // TODO: this is theoretically not watertight, but should cover current use cases
            // try to filter by chargeReferenceForDiscount and lease item dates
            if (chargeReferenceForDiscountItemIfAny!=null
                    && leaseItem.getCharge().getReference().equals(chargeReferenceForDiscountItemIfAny)
                    && leaseItem.getStartDate().equals(getStartDate()) //taking start- and end date into account will filter existing discount items with same charge as well
                    && leaseItem.getEndDate().equals(getEndDate())
            ){
                for (LeaseTerm term : leaseItem.getTerms()){
                    for (PersistedCalculationResult calcResult : persistedCalculationResultRepository.findByLeaseTerm(term)){
                        result = result.add(calcResult.getValue());
                    }
                }
            }
        }
        return result;
    }

    @Action(semantics = SemanticsOf.SAFE)
    @Override
    public LeaseAmendmentItemType getType(){
        return LeaseAmendmentItemType.DISCOUNT;
    }

    @Inject
    PersistedCalculationResultRepository persistedCalculationResultRepository;
}
