package org.estatio.dom.agreement;

import java.util.List;

import org.apache.isis.applib.ApplicationException;
import org.apache.isis.applib.DomainObjectContainer;
import org.apache.isis.applib.annotation.Hidden;
import org.apache.isis.applib.annotation.Immutable;
import org.apache.isis.applib.annotation.MemberOrder;
import org.apache.isis.applib.annotation.NotPersisted;
import org.apache.isis.applib.annotation.Programmatic;
import org.apache.isis.applib.annotation.Title;

import org.estatio.dom.ComparableByTitle;
import org.estatio.dom.EstatioRefDataObject;
import org.estatio.dom.PowerType;
import org.estatio.dom.utils.ClassUtils;

@javax.jdo.annotations.PersistenceCapable
@Immutable
public class AgreementType extends EstatioRefDataObject<AgreementType> implements ComparableByTitle<AgreementType>, PowerType<Agreement> {

    public AgreementType() {
        super("title");
    }
    
    // //////////////////////////////////////


    private String title;

    @MemberOrder(sequence = "1")
    @Title
    public String getTitle() {
        return title;
    }

    public void setTitle(final String title) {
        this.title = title;
    }

    // //////////////////////////////////////

    private String implementationClassName;

    @Hidden
    @MemberOrder(sequence = "2")
    public String getImplementationClassName() {
        return implementationClassName;
    }

    public void setImplementationClassName(final String implementationClassName) {
        this.implementationClassName = implementationClassName;
    }

    // //////////////////////////////////////

    @NotPersisted
    // else Isis tries to persist graph when setting up fixture data.
    public List<AgreementRoleType> getRoles() {
        return AgreementRoleType.applicableTo(this);
    }

    // //////////////////////////////////////

    @Programmatic
    public Agreement create(DomainObjectContainer container) {
        try {
            Class<? extends Agreement> cls = ClassUtils.load(implementationClassName, Agreement.class);
            Agreement agreement = container.newTransientInstance(cls);
            agreement.setAgreementType(this);
            return agreement;
        } catch (Exception ex) {
            throw new ApplicationException(ex);
        }
    }

    // //////////////////////////////////////

    @Programmatic
    @NotPersisted
    public List<AgreementRoleType> getApplicableTo() {
        return agreementRoleTypes.findApplicableTo(this);
    }


    // //////////////////////////////////////

    private AgreementRoleTypes agreementRoleTypes;

    public void injectAgreementRoleTypes(final AgreementRoleTypes agreementRoleTypes) {
        this.agreementRoleTypes = agreementRoleTypes;
    }

    // //////////////////////////////////////

    /**
     * For fixtures
     */
    public static AgreementType create(final String title, final String implementationClassName, final DomainObjectContainer container) {
        final AgreementType agreementType = container.newTransientInstance(AgreementType.class);
        agreementType.setTitle(title);
        agreementType.setImplementationClassName(implementationClassName);
        container.persist(agreementType);
        return agreementType;
    }

}
