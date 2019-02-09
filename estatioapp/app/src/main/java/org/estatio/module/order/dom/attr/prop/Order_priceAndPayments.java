package org.estatio.module.order.dom.attr.prop;

import org.apache.isis.applib.annotation.Mixin;

import org.estatio.module.capex.dom.order.Order;
import org.estatio.module.order.dom.attr.OrderAttributeName;

@Mixin(method="prop")
public class Order_priceAndPayments
        extends Order_attributeValueAbstract {

    public Order_priceAndPayments(final Order order) {
        super(order, OrderAttributeName.CONFIRMATION_PRICE_AND_PAYMENTS);
    }

}