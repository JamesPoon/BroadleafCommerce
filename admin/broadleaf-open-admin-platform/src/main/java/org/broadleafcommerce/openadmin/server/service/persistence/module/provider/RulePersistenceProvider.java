/*
 * Copyright 2008-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.broadleafcommerce.openadmin.server.service.persistence.module.provider;

import org.apache.commons.lang.StringUtils;
import org.broadleafcommerce.common.presentation.client.SupportedFieldType;
import org.broadleafcommerce.common.rule.QuantityBasedRule;
import org.broadleafcommerce.common.rule.SimpleRule;
import org.broadleafcommerce.openadmin.client.dto.BasicFieldMetadata;
import org.broadleafcommerce.openadmin.client.dto.Entity;
import org.broadleafcommerce.openadmin.client.dto.FieldMetadata;
import org.broadleafcommerce.openadmin.client.dto.Property;
import org.broadleafcommerce.openadmin.server.service.persistence.module.FieldManager;
import org.broadleafcommerce.openadmin.server.service.persistence.module.FieldNotAvailableException;
import org.broadleafcommerce.openadmin.server.service.persistence.PersistenceException;
import org.broadleafcommerce.openadmin.server.service.persistence.module.provider.request.AddFilterPropertiesRequest;
import org.broadleafcommerce.openadmin.server.service.persistence.module.provider.request.ExtractValueRequest;
import org.broadleafcommerce.openadmin.server.service.persistence.module.provider.request.PopulateValueRequest;
import org.broadleafcommerce.openadmin.server.service.type.RuleIdentifier;
import org.broadleafcommerce.openadmin.web.rulebuilder.DataDTODeserializer;
import org.broadleafcommerce.openadmin.web.rulebuilder.DataDTOToMVELTranslator;
import org.broadleafcommerce.openadmin.web.rulebuilder.MVELToDataWrapperTranslator;
import org.broadleafcommerce.openadmin.web.rulebuilder.MVELTranslationException;
import org.broadleafcommerce.openadmin.web.rulebuilder.dto.DataDTO;
import org.broadleafcommerce.openadmin.web.rulebuilder.dto.DataWrapper;
import org.broadleafcommerce.openadmin.web.rulebuilder.service.RuleBuilderFieldServiceFactory;
import org.codehaus.jackson.Version;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.module.SimpleModule;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * @author Jeff Fischer
 */
@Component("blRulePersistenceProvider")
@Scope("prototype")
public class RulePersistenceProvider extends PersistenceProviderAdapter {

    protected boolean canHandlePersistence(PopulateValueRequest populateValueRequest) {
        return populateValueRequest.getMetadata().getFieldType() == SupportedFieldType.RULE_WITH_QUANTITY ||
                populateValueRequest.getMetadata().getFieldType() == SupportedFieldType.RULE_SIMPLE;
    }

    protected boolean canHandleExtraction(ExtractValueRequest extractValueRequest) {
        return extractValueRequest.getMetadata().getFieldType() == SupportedFieldType.RULE_WITH_QUANTITY ||
                extractValueRequest.getMetadata().getFieldType() == SupportedFieldType.RULE_SIMPLE;
    }

    @Resource(name = "blRuleBuilderFieldServiceFactory")
    protected RuleBuilderFieldServiceFactory ruleBuilderFieldServiceFactory;

    public boolean populateValue(PopulateValueRequest populateValueRequest) throws PersistenceException {
        if (!canHandlePersistence(populateValueRequest)) {
            return false;
        }
        try {
            switch (populateValueRequest.getMetadata().getFieldType()) {
                case RULE_WITH_QUANTITY:{
                    //currently, this only works with Collection fields
                    Class<?> valueType = getListFieldType(populateValueRequest.getRequestedInstance(), populateValueRequest
                            .getFieldManager(), populateValueRequest.getProperty(), populateValueRequest.getPersistenceManager());
                    if (valueType == null) {
                        throw new IllegalAccessException("Unable to determine the valueType for the rule field (" +
                                populateValueRequest.getProperty().getName() + ")");
                    }
                    DataDTOToMVELTranslator translator = new DataDTOToMVELTranslator();
                    Collection<QuantityBasedRule> rules;
                    try {
                        rules = (Collection<QuantityBasedRule>) populateValueRequest.getFieldManager().getFieldValue
                                (populateValueRequest.getRequestedInstance(), populateValueRequest.getProperty().getName());
                    } catch (FieldNotAvailableException e) {
                        throw new IllegalArgumentException(e);
                    }
                    populateQuantityBaseRuleCollection(translator, RuleIdentifier.ENTITY_KEY_MAP.get
                            (populateValueRequest.getMetadata().getRuleIdentifier()),
                            populateValueRequest.getMetadata().getRuleIdentifier(), populateValueRequest.getRequestedValue(), rules, valueType);
                    break;
                }
                case RULE_SIMPLE:{
                    DataDTOToMVELTranslator translator = new DataDTOToMVELTranslator();
                    String mvel = convertMatchRuleJsonToMvel(translator, RuleIdentifier.ENTITY_KEY_MAP.get(populateValueRequest.getMetadata().getRuleIdentifier()),
                            populateValueRequest.getMetadata().getRuleIdentifier(), populateValueRequest.getRequestedValue());
                    Class<?> valueType = null;
                    //is this a regular field?
                    if (!populateValueRequest.getProperty().getName().contains(FieldManager.MAPFIELDSEPARATOR)) {
                        valueType = populateValueRequest.getReturnType();
                    } else {
                        String valueClassName = populateValueRequest.getMetadata().getMapFieldValueClass();
                        if (valueClassName != null) {
                            valueType = Class.forName(valueClassName);
                        }
                        if (valueType == null) {
                            valueType = populateValueRequest.getReturnType();
                        }
                    }
                    if (valueType == null) {
                        throw new IllegalAccessException("Unable to determine the valueType for the rule field (" + populateValueRequest.getProperty().getName() + ")");
                    }
                    //This is a simple String field (or String map field)
                    if (String.class.isAssignableFrom(valueType)) {
                        populateValueRequest.getFieldManager().setFieldValue(populateValueRequest.getRequestedInstance(), populateValueRequest.getProperty().getName(), mvel);
                    }
                    if (SimpleRule.class.isAssignableFrom(valueType)) {
                        //see if there's an existing rule
                        SimpleRule rule;
                        try {
                            rule = (SimpleRule) populateValueRequest.getFieldManager().getFieldValue(populateValueRequest.getRequestedInstance(), populateValueRequest.getProperty().getName());
                        } catch (FieldNotAvailableException e) {
                            throw new IllegalArgumentException(e);
                        }
                        if (rule != null) {
                            rule.setMatchRule(mvel);
                        } else {
                            //create a new instance, persist and set
                            rule = (SimpleRule) valueType.newInstance();
                            rule.setMatchRule(mvel);
                            populateValueRequest.getPersistenceManager().getDynamicEntityDao().persist(rule);
                            populateValueRequest.getFieldManager().setFieldValue(populateValueRequest.getRequestedInstance(), populateValueRequest.getProperty().getName(), rule);
                        }
                    }
                    break;
                }
            }
        } catch (Exception e) {
            throw new PersistenceException(e);
        }
        return true;
    }

    @Override
    public boolean extractValue(ExtractValueRequest extractValueRequest) throws PersistenceException {
        if (!canHandleExtraction(extractValueRequest)) {
            return false;
        }
        String val = null;
        ObjectMapper mapper = new ObjectMapper();
        MVELToDataWrapperTranslator translator = new MVELToDataWrapperTranslator();
        if (extractValueRequest.getMetadata().getFieldType()== SupportedFieldType.RULE_SIMPLE) {
            if (extractValueRequest.getRequestedValue() != null) {
                if (extractValueRequest.getRequestedValue() instanceof String) {
                    val = (String) extractValueRequest.getRequestedValue();
                    extractValueRequest.getRequestedProperty().setValue(val);
                    extractValueRequest.getRequestedProperty().setDisplayValue(extractValueRequest.getDisplayVal());
                }
                if (extractValueRequest.getRequestedValue() instanceof SimpleRule) {
                    SimpleRule simpleRule = (SimpleRule) extractValueRequest.getRequestedValue();
                    if (simpleRule != null) {
                        val = simpleRule.getMatchRule();
                        extractValueRequest.getRequestedProperty().setValue(val);
                        extractValueRequest.getRequestedProperty().setDisplayValue(extractValueRequest.getDisplayVal());
                    }
                }
            }
            Property jsonProperty = convertSimpleRuleToJson(translator, mapper, val,
                    extractValueRequest.getMetadata().getName() + "Json", extractValueRequest.getMetadata().getRuleIdentifier());
            extractValueRequest.getProps().add(jsonProperty);
        }
        if (extractValueRequest.getMetadata().getFieldType()==SupportedFieldType.RULE_WITH_QUANTITY) {
            if (extractValueRequest.getRequestedValue() != null) {
                if (extractValueRequest.getRequestedValue() instanceof Collection) {
                    //these quantity rules are in a list - this is a special, valid case for quantity rules
                    Property jsonProperty = convertQuantityBasedRuleToJson(translator,
                            mapper, (Collection<QuantityBasedRule>) extractValueRequest
                            .getRequestedValue(),
                            extractValueRequest.getMetadata().getName() + "Json", extractValueRequest.getMetadata()
                            .getRuleIdentifier());
                    extractValueRequest.getProps().add(jsonProperty);
                } else {
                    //TODO support a single quantity based rule
                    throw new UnsupportedOperationException("RULE_WITH_QUANTITY type is currently only supported" +
                            "on collection fields. A single field with this type is not currently supported.");
                }
            }
        }
        return true;
    }

    @Override
    public boolean filterProperties(AddFilterPropertiesRequest addFilterPropertiesRequest) {
        //This may contain rule Json fields - convert and filter out
        List<Property> propertyList = new ArrayList<Property>();
        propertyList.addAll(Arrays.asList(addFilterPropertiesRequest.getEntity().getProperties()));
        Iterator<Property> itr = propertyList.iterator();
        List<Property> additionalProperties = new ArrayList<Property>();
        while(itr.hasNext()) {
            Property prop = itr.next();
            if (prop.getName().endsWith("Json")) {
                for (Map.Entry<String, FieldMetadata> entry : addFilterPropertiesRequest.getRequestedProperties().entrySet()) {
                    if (prop.getName().startsWith(entry.getKey())) {
                        BasicFieldMetadata originalFM = (BasicFieldMetadata) entry.getValue();
                        if (originalFM.getFieldType() == SupportedFieldType.RULE_SIMPLE ||
                                originalFM.getFieldType() == SupportedFieldType.RULE_WITH_QUANTITY) {
                            Property orginalProp = addFilterPropertiesRequest.getEntity().findProperty(originalFM
                                    .getName());
                            if (orginalProp == null) {
                                orginalProp = new Property();
                                orginalProp.setName(originalFM.getName());
                                additionalProperties.add(orginalProp);
                            }
                            orginalProp.setValue(prop.getValue());
                            itr.remove();
                            break;
                        }
                    }
                }
            }
        }
        propertyList.addAll(additionalProperties);
        addFilterPropertiesRequest.getEntity().setProperties(propertyList.toArray(new Property[propertyList.size()]));
        return true;
    }

    protected Property convertQuantityBasedRuleToJson(MVELToDataWrapperTranslator translator, ObjectMapper mapper,
                        Collection<QuantityBasedRule> quantityBasedRules, String jsonProp, String fieldService) {

        int k=0;
        Entity[] targetItemCriterias = new Entity[quantityBasedRules.size()];
        for (QuantityBasedRule quantityBasedRule : quantityBasedRules) {
            Property[] properties = new Property[3];
            Property mvelProperty = new Property();
            mvelProperty.setName("matchRule");
            mvelProperty.setValue(quantityBasedRule.getMatchRule());
            Property quantityProperty = new Property();
            quantityProperty.setName("quantity");
            quantityProperty.setValue(quantityBasedRule.getQuantity().toString());
            Property idProperty = new Property();
            idProperty.setName("id");
            idProperty.setValue(quantityBasedRule.getId().toString());
            properties[0] = mvelProperty;
            properties[1] = quantityProperty;
            properties[2] = idProperty;
            Entity criteria = new Entity();
            criteria.setProperties(properties);
            targetItemCriterias[k] = criteria;
            k++;
        }

        String json;
        try {
            DataWrapper oiWrapper = translator.createRuleData(targetItemCriterias, "matchRule", "quantity", "id",
                    ruleBuilderFieldServiceFactory.createInstance(fieldService));
            json = mapper.writeValueAsString(oiWrapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Property p = new Property();
        p.setName(jsonProp);
        p.setValue(json);

        return p;
    }

    protected Property convertSimpleRuleToJson(MVELToDataWrapperTranslator translator, ObjectMapper mapper,
                                                   String matchRule, String jsonProp, String fieldService) {
        Entity[] matchCriteria = new Entity[1];
        Property[] properties = new Property[1];
        Property mvelProperty = new Property();
        mvelProperty.setName("matchRule");
        mvelProperty.setValue(matchRule == null?"":matchRule);
        properties[0] = mvelProperty;
        Entity criteria = new Entity();
        criteria.setProperties(properties);
        matchCriteria[0] = criteria;

        String json;
        try {
            DataWrapper orderWrapper = translator.createRuleData(matchCriteria, "matchRule", null, null,
                    ruleBuilderFieldServiceFactory.createInstance(fieldService));
            json = mapper.writeValueAsString(orderWrapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Property p = new Property();
        p.setName(jsonProp);
        p.setValue(json);

        return p;
    }

    protected void populateQuantityBaseRuleCollection(DataDTOToMVELTranslator translator, String entityKey,
                                                          String fieldService, String jsonPropertyValue,
                                                          Collection<QuantityBasedRule> criteriaList, Class<?> memberType) {
        if (!StringUtils.isEmpty(jsonPropertyValue)) {
            DataWrapper dw = convertJsonToDataWrapper(jsonPropertyValue);
            if (dw != null) {
                List<QuantityBasedRule> updatedRules = new ArrayList<QuantityBasedRule>();
                for (DataDTO dto : dw.getData()) {
                    if (dto.getId() != null) {
                        checkId: {
                            //updates are comprehensive, even data that was not changed
                            //is submitted here
                            //Update Existing Criteria
                            for (QuantityBasedRule quantityBasedRule : criteriaList) {
                                if (dto.getId().equals(quantityBasedRule.getId())){
                                    //don't update if the data has not changed
                                    if (!quantityBasedRule.getQuantity().equals(dto.getQuantity())) {
                                        quantityBasedRule.setQuantity(dto.getQuantity());
                                    }
                                    try {
                                        String mvel = translator.createMVEL(entityKey, dto,
                                                    ruleBuilderFieldServiceFactory.createInstance(fieldService));
                                        if (!quantityBasedRule.getMatchRule().equals(mvel)) {
                                            quantityBasedRule.setMatchRule(mvel);
                                        }
                                    } catch (MVELTranslationException e) {
                                        throw new RuntimeException(e);
                                    }
                                    updatedRules.add(quantityBasedRule);
                                    break checkId;
                                }
                            }
                            throw new IllegalArgumentException("Unable to update the rule of type (" + memberType.getName() +
                                    ") because an update was requested for id (" + dto.getId() + "), which does not exist.");
                        }
                    } else {
                        //Create a new Criteria
                        QuantityBasedRule quantityBasedRule;
                        try {
                            quantityBasedRule = (QuantityBasedRule) memberType.newInstance();
                            quantityBasedRule.setQuantity(dto.getQuantity());
                            quantityBasedRule.setMatchRule(translator.createMVEL(entityKey, dto,
                                    ruleBuilderFieldServiceFactory.createInstance(fieldService)));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        criteriaList.add(quantityBasedRule);
                        updatedRules.add(quantityBasedRule);
                    }
                }
                //if an item was not included in the comprehensive submit from the client, we can assume that the
                //listing was deleted, so we remove it here.
                Iterator<QuantityBasedRule> itr = criteriaList.iterator();
                while(itr.hasNext()) {
                    checkForRemove: {
                        QuantityBasedRule original = itr.next();
                        for (QuantityBasedRule quantityBasedRule : updatedRules) {
                            if (original.equals(quantityBasedRule)) {
                                break checkForRemove;
                            }
                        }
                        itr.remove();
                    }
                }
            }
        }
    }

    protected DataWrapper convertJsonToDataWrapper(String json) {
        ObjectMapper mapper = new ObjectMapper();
        DataDTODeserializer dtoDeserializer = new DataDTODeserializer();
        SimpleModule module = new SimpleModule("DataDTODeserializerModule", new Version(1, 0, 0, null));
        module.addDeserializer(DataDTO.class, dtoDeserializer);
        mapper.registerModule(module);
        if (json == null || "[]".equals(json)){
            return null;
        }

        try {
            return mapper.readValue(json, DataWrapper.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected String convertMatchRuleJsonToMvel(DataDTOToMVELTranslator translator, String entityKey,
                                                      String fieldService, String jsonPropertyValue) {
        String mvel = null;
        if (jsonPropertyValue != null) {
            DataWrapper dw = convertJsonToDataWrapper(jsonPropertyValue);
            //there can only be one DataDTO for an appliesTo* rule
            if (dw != null && dw.getData().size() == 1) {
                DataDTO dto = dw.getData().get(0);
                try {
                    mvel = translator.createMVEL(entityKey, dto,
                            ruleBuilderFieldServiceFactory.createInstance(fieldService));
                } catch (MVELTranslationException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        return mvel;
    }

    @Override
    public int getOrder() {
        return PersistenceProvider.RULE;
    }
}
