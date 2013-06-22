package org.estatio.dom.agreement;

import org.jmock.Expectations;
import org.jmock.auto.Mock;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import org.apache.isis.core.unittestsupport.jmocking.JUnitRuleMockery2;
import org.apache.isis.core.unittestsupport.jmocking.JUnitRuleMockery2.Mode;

public class AgreementRoleTypeTest_applicableTo {

    private AgreementRoleType art;
    private AgreementType at;
    
    @Mock
    private AgreementRoleTypes mockAgreementRoleTypes;
    
    @Rule
    public JUnitRuleMockery2 context = JUnitRuleMockery2.createFor(Mode.INTERFACES_AND_CLASSES);

    @Before
    public void setUp() throws Exception {
        at = new AgreementType();
        at.injectAgreementRoleTypes(mockAgreementRoleTypes);
        
        art = new AgreementRoleType();
        art.setAppliesTo(at);
    }
    

    @Test
    public void delegatesToService() {
        context.checking(new Expectations() {
            {
                oneOf(mockAgreementRoleTypes).findApplicableTo(at);
            }
        }
        );

        AgreementRoleType.applicableTo(at);
    }
    


}
