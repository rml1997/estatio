/*
 *
 *  Copyright 2012-2015 Eurocommercial Properties NV
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.estatio.module.capex.dom.project;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.jdo.annotations.Column;
import javax.jdo.annotations.DatastoreIdentity;
import javax.jdo.annotations.IdGeneratorStrategy;
import javax.jdo.annotations.IdentityType;
import javax.jdo.annotations.PersistenceCapable;
import javax.jdo.annotations.Queries;
import javax.jdo.annotations.Query;
import javax.jdo.annotations.Unique;
import javax.jdo.annotations.Version;
import javax.jdo.annotations.VersionStrategy;

import com.google.inject.internal.cglib.core.$ReflectUtils;

import org.joda.time.LocalDate;

import org.apache.isis.applib.annotation.Action;
import org.apache.isis.applib.annotation.ActionLayout;
import org.apache.isis.applib.annotation.BookmarkPolicy;
import org.apache.isis.applib.annotation.Contributed;
import org.apache.isis.applib.annotation.DomainObject;
import org.apache.isis.applib.annotation.DomainObjectLayout;
import org.apache.isis.applib.annotation.Editing;
import org.apache.isis.applib.annotation.Optionality;
import org.apache.isis.applib.annotation.Parameter;
import org.apache.isis.applib.annotation.Programmatic;
import org.apache.isis.applib.annotation.PropertyLayout;
import org.apache.isis.applib.annotation.SemanticsOf;
import org.apache.isis.applib.annotation.Where;
import org.apache.isis.applib.services.factory.FactoryService;
import org.apache.isis.applib.services.repository.RepositoryService;

import org.isisaddons.module.security.dom.tenancy.ApplicationTenancy;

import org.incode.module.base.dom.types.MoneyType;
import org.incode.module.base.dom.utils.TitleBuilder;

import org.estatio.module.base.dom.UdoDomainObject;
import org.estatio.module.asset.dom.FixedAsset;
import org.estatio.module.asset.dom.Property;
import org.estatio.module.capex.dom.invoice.contributions.ProjectItem_IncomingInvoiceItems;
import org.estatio.module.capex.dom.items.FinancialItem;
import org.estatio.module.capex.dom.items.FinancialItemType;
import org.estatio.module.capex.dom.order.contributions.ProjectItem_OrderItems;
import org.estatio.module.charge.dom.Charge;
import org.estatio.module.tax.dom.Tax;

import lombok.Getter;
import lombok.Setter;

@PersistenceCapable(
		identityType = IdentityType.DATASTORE
		,schema = "dbo"
)
@DatastoreIdentity(strategy = IdGeneratorStrategy.NATIVE, column = "id")
@Version(strategy = VersionStrategy.VERSION_NUMBER, column = "version")
@Queries({
		@Query(name = "findByProjectAndCharge", language = "JDOQL", value = "SELECT "
				+ "FROM org.estatio.module.capex.dom.project.ProjectItem "
				+ "WHERE project == :project "
				+ " && charge == :charge")
})
@Unique(name = "ProjectItem_project_charge_UNQ", members = { "project", "charge" })
@DomainObject(
		editing = Editing.DISABLED,
		objectType = "org.estatio.capex.dom.project.ProjectItem"
)
@DomainObjectLayout(bookmarking = BookmarkPolicy.AS_CHILD)
public class ProjectItem extends UdoDomainObject<ProjectItem> {

	public String title(){
		return TitleBuilder.start().withParent(getProject()).withName(getCharge()).toString();
	}

	public ProjectItem() {
		super("project, charge");
	}

	@Column(allowsNull = "false", name = "projectId")
	@PropertyLayout(hidden = Where.REFERENCES_PARENT)
	@Getter @Setter
	private Project project;

	@Column(allowsNull = "false", name = "chargeId")
	@Getter @Setter
	private Charge charge;

	@Column(allowsNull = "false")
	@Getter @Setter
	private String description;

	@Action(semantics = SemanticsOf.SAFE)
	@ActionLayout(contributed = Contributed.AS_ASSOCIATION)
	public BigDecimal getBudgetedAmount(){
		final ProjectBudget projectBudget = getProject().getProjectBudget();
		if (projectBudget==null) return null;
		return projectBudget.getAmountFor(projectBudget, this);
	}

	@Column(allowsNull = "true", name = "propertyId")
	@Getter @Setter
	private Property property;

	@PropertyLayout(hidden = Where.ALL_TABLES)
	@Column(allowsNull = "true", name = "taxId")
	@Getter @Setter
	private Tax tax;

	@Action(semantics = SemanticsOf.NON_IDEMPOTENT_ARE_YOU_SURE)
	public void delete(){
		if (getProject().isApproved()) return;
		repositoryService.remove(this);
	}

	public boolean hideDelete() {
		if (getProject().isApproved()) return true;
		// TODO: use events when decoupling in the future
		if (factoryService.mixin(ProjectItem_OrderItems.class, this).orderItems().isEmpty() &&
			factoryService.mixin(ProjectItem_IncomingInvoiceItems.class, this).invoiceItems().isEmpty()) return false;
		return true;
	}

	public List<BudgetForecastItem> getBudgetForecastItems(){
		List<BudgetForecastItem> result = new ArrayList<>();
		final List<BudgetForecast> forecasts = budgetForecastRepositoryAndFactory.findByProject(getProject());

		forecasts.forEach(f->{
			result.add(f.findItemFor(this));
		});
		return result.stream().sorted(Comparator.comparing(BudgetForecastItem::getForecast).reversed()).collect(
				Collectors.toList());
	}

	@Override
	@Programmatic
	public ApplicationTenancy getApplicationTenancy() {
		return getProject().getApplicationTenancy();
	}

	@Inject
	RepositoryService repositoryService;

	@Inject
	FactoryService factoryService;

	@Inject
	BudgetForecastRepositoryAndFactory budgetForecastRepositoryAndFactory;
}
