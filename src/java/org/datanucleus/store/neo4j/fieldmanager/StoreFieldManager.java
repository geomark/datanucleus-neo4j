/**********************************************************************
Copyright (c) 2012 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
    ...
**********************************************************************/
package org.datanucleus.store.neo4j.fieldmanager;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.ExecutionContext;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.FieldRole;
import org.datanucleus.metadata.MetaDataUtils;
import org.datanucleus.metadata.RelationType;
import org.datanucleus.state.ObjectProvider;
import org.datanucleus.store.fieldmanager.AbstractStoreFieldManager;
import org.datanucleus.store.fieldmanager.FieldManager;
import org.datanucleus.store.neo4j.Neo4jStoreManager;
import org.datanucleus.store.neo4j.Neo4jUtils;
import org.datanucleus.store.schema.naming.ColumnType;
import org.datanucleus.store.types.TypeManager;
import org.datanucleus.store.types.converters.TypeConverter;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.Relationship;

/**
 * Field Manager for putting values from a POJO into a Neo4j Node.
 */
public class StoreFieldManager extends AbstractStoreFieldManager
{
    /** Node/Relationship that we are populating with properties representing the fields of the POJO. */
    protected PropertyContainer propObj;

    /** Metadata of the owner field if this is for an embedded object. */
    protected AbstractMemberMetaData ownerMmd = null;

    public StoreFieldManager(ObjectProvider op, PropertyContainer propObj, boolean insert)
    {
        super(op, insert);
        this.propObj = propObj;
    }

    protected String getPropName(int fieldNumber)
    {
        return ec.getStoreManager().getNamingFactory().getColumnName(
            cmd.getMetaDataForManagedMemberAtAbsolutePosition(fieldNumber), ColumnType.COLUMN);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.fieldmanager.AbstractFieldManager#storeBooleanField(int, boolean)
     */
    @Override
    public void storeBooleanField(int fieldNumber, boolean value)
    {
        if (!isStorable(fieldNumber))
        {
            return;
        }
        propObj.setProperty(getPropName(fieldNumber), value);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.fieldmanager.AbstractFieldManager#storeByteField(int, byte)
     */
    @Override
    public void storeByteField(int fieldNumber, byte value)
    {
        if (!isStorable(fieldNumber))
        {
            return;
        }
        propObj.setProperty(getPropName(fieldNumber), value);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.fieldmanager.AbstractFieldManager#storeCharField(int, char)
     */
    @Override
    public void storeCharField(int fieldNumber, char value)
    {
        if (!isStorable(fieldNumber))
        {
            return;
        }
        propObj.setProperty(getPropName(fieldNumber), value);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.fieldmanager.AbstractFieldManager#storeDoubleField(int, double)
     */
    @Override
    public void storeDoubleField(int fieldNumber, double value)
    {
        if (!isStorable(fieldNumber))
        {
            return;
        }
        propObj.setProperty(getPropName(fieldNumber), value);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.fieldmanager.AbstractFieldManager#storeFloatField(int, float)
     */
    @Override
    public void storeFloatField(int fieldNumber, float value)
    {
        if (!isStorable(fieldNumber))
        {
            return;
        }
        propObj.setProperty(getPropName(fieldNumber), value);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.fieldmanager.AbstractFieldManager#storeIntField(int, int)
     */
    @Override
    public void storeIntField(int fieldNumber, int value)
    {
        if (!isStorable(fieldNumber))
        {
            return;
        }
        propObj.setProperty(getPropName(fieldNumber), value);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.fieldmanager.AbstractFieldManager#storeLongField(int, long)
     */
    @Override
    public void storeLongField(int fieldNumber, long value)
    {
        if (!isStorable(fieldNumber))
        {
            return;
        }
        propObj.setProperty(getPropName(fieldNumber), value);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.fieldmanager.AbstractFieldManager#storeShortField(int, short)
     */
    @Override
    public void storeShortField(int fieldNumber, short value)
    {
        if (!isStorable(fieldNumber))
        {
            return;
        }
        propObj.setProperty(getPropName(fieldNumber), value);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.fieldmanager.AbstractFieldManager#storeStringField(int, java.lang.String)
     */
    @Override
    public void storeStringField(int fieldNumber, String value)
    {
        if (!isStorable(fieldNumber))
        {
            return;
        }
        if (value == null)
        {
            if (!insert)
            {
                propObj.removeProperty(getPropName(fieldNumber));
            }
            return;
        }
        propObj.setProperty(getPropName(fieldNumber), value);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.fieldmanager.AbstractFieldManager#storeObjectField(int, java.lang.Object)
     */
    @Override
    public void storeObjectField(int fieldNumber, Object value)
    {
        AbstractMemberMetaData mmd = cmd.getMetaDataForManagedMemberAtAbsolutePosition(fieldNumber);
        if (!isStorable(mmd))
        {
            return;
        }

        String propName = ec.getStoreManager().getNamingFactory().getColumnName(mmd, ColumnType.COLUMN);
        if (!insert && propObj.hasProperty(propName) && value == null)
        {
            // Updating the field, it had a value but this time is null, so remove it
            propObj.removeProperty(propName);
            return;
        }

        ClassLoaderResolver clr = ec.getClassLoaderResolver();
        RelationType relationType = mmd.getRelationType(clr);
        if (relationType != RelationType.NONE)
        {
            if (MetaDataUtils.getInstance().isMemberEmbedded(ec.getMetaDataManager(), clr, mmd, relationType, ownerMmd))
            {
                // Embedded Field
                if (RelationType.isRelationSingleValued(relationType) && value != null)
                {
                    // Embedded PC object
                    // TODO Cater for nulled embedded object on update
                    if (ownerMmd != null)
                    {
                        // Detect bidirectional relation so we know when to stop embedding
                        if (RelationType.isBidirectional(relationType))
                        {
                            // Field has mapped-by, so just use that
                            if ((ownerMmd.getMappedBy() != null && mmd.getName().equals(ownerMmd.getMappedBy())) ||
                                (mmd.getMappedBy() != null && ownerMmd.getName().equals(mmd.getMappedBy())))
                            {
                                // This is other side of owner bidirectional, so omit
                                return;
                            }
                        }
                        else 
                        {
                            // mapped-by not set but could have owner-field
                            if (ownerMmd.getEmbeddedMetaData() != null &&
                                ownerMmd.getEmbeddedMetaData().getOwnerMember() != null &&
                                ownerMmd.getEmbeddedMetaData().getOwnerMember().equals(mmd.getName()))
                            {
                                // This is the owner-field linking back to the owning object so stop
                                return;
                            }
                        }
                    }

                    AbstractClassMetaData embcmd = ec.getMetaDataManager().getMetaDataForClass(value.getClass(), clr);
                    if (embcmd == null)
                    {
                        throw new NucleusUserException("Field " + mmd.getFullFieldName() +
                            " specified as embedded but metadata not found for the class of type " + mmd.getTypeName());
                    }

                    // Extract the owner member metadata for this embedded object
                    AbstractMemberMetaData embMmd = mmd;
                    if (ownerMmd != null)
                    {
                        // Nested, so use from the embeddedMetaData
                        embMmd = ownerMmd.getEmbeddedMetaData().getMemberMetaData()[fieldNumber];
                    }

                    // TODO Cater for inherited embedded objects (discriminator)

                    ObjectProvider embOP = ec.findObjectProviderForEmbedded(value, op, mmd);
                    FieldManager ffm = new StoreEmbeddedFieldManager(embOP, propObj, insert, embMmd);
                    embOP.provideFields(embcmd.getAllMemberPositions(), ffm);
                    return;
                }
                // TODO Support more types of embedded objects
                throw new NucleusUserException("Don't currently support embedded field : " + mmd.getFullFieldName());
            }
        }

        if (mmd.isSerialized())
        {
            if (value == null)
            {
                return;
            }
            if (value instanceof Serializable)
            {
                TypeConverter<Serializable, String> conv = ec.getTypeManager().getTypeConverterForType(Serializable.class, String.class);
                String strValue = conv.toDatastoreType((Serializable) value);
                propObj.setProperty(propName, strValue);
                return;
            }
            else
            {
                throw new NucleusUserException("Field " + mmd.getFullFieldName() + " is marked as serialised, but value is not Serializable");
            }
        }

        if (RelationType.isRelationSingleValued(relationType))
        {
            if (!(propObj instanceof Node))
            {
                // TODO Work out if this is the source or the target
                throw new NucleusUserException("Object " + op + " is mapped to a Relationship. Not yet supported");
            }

            Node node = (Node)propObj;
            processSingleValuedRelationForNode(mmd, relationType, value, ec, clr, node);
            return;
        }
        else if (RelationType.isRelationMultiValued(relationType))
        {
            if (!(propObj instanceof Node))
            {
                // Any object mapped as a Relationship cannot have multi-value relations, only a source and target
                throw new NucleusUserException("Object " + op + " is mapped to a Relationship but has field " + 
                    mmd.getFullFieldName() + " which is multi-valued. This is illegal");
            }

            Node node = (Node)propObj;
            processMultiValuedRelationForNode(mmd, relationType, value, ec, clr, node);
            return;
        }

        if (value == null)
        {
            // Don't add the property when null
            return;
        }

        if (mmd.getTypeConverterName() != null)
        {
            // User-defined type converter
            TypeManager typeMgr = ec.getNucleusContext().getTypeManager();
            TypeConverter conv = typeMgr.getTypeConverterForName(mmd.getTypeConverterName());
            propObj.setProperty(propName, conv.toDatastoreType(value));
        }
        else
        {
            Object storedValue = Neo4jUtils.getStoredValueForField(ec, mmd, value, FieldRole.ROLE_FIELD);
            if (storedValue != null)
            {
                // Neo4j doesn't allow null values
                propObj.setProperty(propName, storedValue);
            }
        }
        op.wrapSCOField(fieldNumber, value, false, false, true);
    }

    protected void processSingleValuedRelationForNode(AbstractMemberMetaData mmd, RelationType relationType, Object value,
            ExecutionContext ec, ClassLoaderResolver clr, Node node)
    {
        // 1-1/N-1 Make sure it is persisted and form the relation
        Object valuePC = (value != null ? ec.persistObjectInternal(value, null, -1, -1) : null);
        ObjectProvider relatedOP = (value != null ? ec.findObjectProvider(valuePC) : null);

        if (relationType != RelationType.MANY_TO_ONE_BI && mmd.getMappedBy() == null)
        {
            // Only have a Relationship if this side owns the relation
            Node relatedNode = (Node)
                (value != null ? Neo4jUtils.getPropertyContainerForObjectProvider(propObj.getGraphDatabase(), relatedOP) : null);

            boolean hasRelation = false;
            if (!insert)
            {
                // Check for old value and remove Relationship if to a different Node
                Iterable<Relationship> rels = node.getRelationships(DNRelationshipType.SINGLE_VALUED);
                Iterator<Relationship> relIter = rels.iterator();
                while (relIter.hasNext())
                {
                    Relationship rel = relIter.next();
                    if (rel.getProperty(Neo4jStoreManager.RELATIONSHIP_FIELD_NAME).equals(mmd.getName()))
                    {
                        // Check if existing relationship for this field is to the same node
                        Node currentNode = rel.getOtherNode(node);
                        if (currentNode.equals(relatedNode))
                        {
                            hasRelation = true;
                            break;
                        }
                        else
                        {
                            // Remove old Relationship TODO Cascade delete?
                            rel.delete();
                        }
                    }
                }
            }

            if (!hasRelation && relatedNode != null)
            {
                // Add the new Relationship
                Relationship rel = node.createRelationshipTo(relatedNode, DNRelationshipType.SINGLE_VALUED);
                rel.setProperty(Neo4jStoreManager.RELATIONSHIP_FIELD_NAME, mmd.getName());
                if (RelationType.isBidirectional(relationType))
                {
                    AbstractMemberMetaData[] relMmds = mmd.getRelatedMemberMetaData(clr);
                    rel.setProperty(Neo4jStoreManager.RELATIONSHIP_FIELD_NAME_NONOWNER, relMmds[0].getName());
                }
            }
        }
    }

    protected void processMultiValuedRelationForNode(AbstractMemberMetaData mmd, RelationType relationType, Object value,
            ExecutionContext ec, ClassLoaderResolver clr, Node node)
    {
        if (mmd.hasCollection())
        {
            Collection coll = (Collection)value;
            List<Node> relNodes = new ArrayList<Node>();
            if (value != null)
            {
                // Reachability : Persist any objects that are not yet persistent, gathering Node objects
                Iterator collIter = coll.iterator();
                while (collIter.hasNext())
                {
                    if (mmd.getCollection().isSerializedElement())
                    {
                        throw new NucleusUserException("Don't currently support serialised collection elements at " + mmd.getFullFieldName());
                    }

                    Object element = collIter.next();
                    if (element != null)
                    {
                        Object elementPC = ec.persistObjectInternal(element, null, -1, -1);
                        ObjectProvider relatedOP = ec.findObjectProvider(elementPC);
                        Node relatedNode = (Node)Neo4jUtils.getPropertyContainerForObjectProvider(propObj.getGraphDatabase(), relatedOP);
                        relNodes.add(relatedNode);
                    }
                    else
                    {
                        throw new NucleusUserException("Dont currently support having null elements in collections : " + mmd.getFullFieldName());
                    }
                }
            }

            if (relationType != RelationType.ONE_TO_MANY_BI && relationType != RelationType.ONE_TO_MANY_UNI &&
                !(relationType == RelationType.MANY_TO_MANY_BI && mmd.getMappedBy() == null))
            {
                // We only store relations when we are the owner
                return;
            }

            if (insert)
            {
                // Insert of the collection, so create Relationship for each
                int index = 0;
                for (Node newNode : relNodes)
                {
                    Relationship rel = node.createRelationshipTo(newNode, DNRelationshipType.MULTI_VALUED);
                    rel.setProperty(Neo4jStoreManager.RELATIONSHIP_FIELD_NAME, mmd.getName());
                    if (coll instanceof List)
                    {
                        rel.setProperty(Neo4jStoreManager.RELATIONSHIP_INDEX_NAME, index);
                        index++;
                    }
                    if (RelationType.isBidirectional(relationType))
                    {
                        AbstractMemberMetaData[] relMmds = mmd.getRelatedMemberMetaData(clr);
                        rel.setProperty(Neo4jStoreManager.RELATIONSHIP_FIELD_NAME_NONOWNER, relMmds[0].getName());
                    }
                }
            }
            else
            {
                // Update of the collection so remove existing Relationship and create new
                // TODO Handle better detecting which are still present and which new/updated
                deleteRelationshipsForMultivaluedMember(node, mmd);

                int index = 0;
                for (Node newNode : relNodes)
                {
                    Relationship rel = node.createRelationshipTo(newNode, DNRelationshipType.MULTI_VALUED);
                    rel.setProperty(Neo4jStoreManager.RELATIONSHIP_FIELD_NAME, mmd.getName());
                    if (coll instanceof List)
                    {
                        rel.setProperty(Neo4jStoreManager.RELATIONSHIP_INDEX_NAME, index);
                        index++;
                    }
                    if (RelationType.isBidirectional(relationType))
                    {
                        AbstractMemberMetaData[] relMmds = mmd.getRelatedMemberMetaData(clr);
                        rel.setProperty(Neo4jStoreManager.RELATIONSHIP_FIELD_NAME_NONOWNER, relMmds[0].getName());
                    }
                }
            }
        }
        else if (mmd.hasArray())
        {
            List<Node> relNodes = new ArrayList<Node>();

            // Reachability : Persist any objects that are not yet persistent, gathering Node objects
            if (value != null)
            {
                for (int i=0;i<Array.getLength(value);i++)
                {
                    if (mmd.getArray().isSerializedElement())
                    {
                        throw new NucleusUserException("Don't currently support serialised array elements at " + mmd.getFullFieldName());
                    }
                    Object element = Array.get(value, i);
                    if (element != null)
                    {
                        Object elementPC = ec.persistObjectInternal(element, null, -1, -1);
                        ObjectProvider relatedOP = ec.findObjectProvider(elementPC);
                        Node relatedNode = (Node)Neo4jUtils.getPropertyContainerForObjectProvider(propObj.getGraphDatabase(), relatedOP);
                        relNodes.add(relatedNode);
                    }
                    else
                    {
                        throw new NucleusUserException("Dont currently support having null elements in arrays : " + mmd.getFullFieldName());
                    }
                }
            }

            if (relationType != RelationType.ONE_TO_MANY_BI && relationType != RelationType.ONE_TO_MANY_UNI &&
                !(relationType == RelationType.MANY_TO_MANY_BI && mmd.getMappedBy() == null))
            {
                // We only store relations when we are the owner
                return;
            }

            if (insert)
            {
                // Insert of the array, so create Relationship for each
                int index = 0;
                for (Node newNode : relNodes)
                {
                    Relationship rel = node.createRelationshipTo(newNode, DNRelationshipType.MULTI_VALUED);
                    rel.setProperty(Neo4jStoreManager.RELATIONSHIP_FIELD_NAME, mmd.getName());
                    rel.setProperty(Neo4jStoreManager.RELATIONSHIP_INDEX_NAME, index);
                    if (RelationType.isBidirectional(relationType))
                    {
                        AbstractMemberMetaData[] relMmds = mmd.getRelatedMemberMetaData(clr);
                        rel.setProperty(Neo4jStoreManager.RELATIONSHIP_FIELD_NAME_NONOWNER, relMmds[0].getName());
                    }
                    index++;
                }
            }
            else
            {
                // Update of the array so remove existing Relationship and create new
                // TODO Handle better detecting which are still present and which new/updated
                deleteRelationshipsForMultivaluedMember(node, mmd);

                int index = 0;
                for (Node newNode : relNodes)
                {
                    Relationship rel = node.createRelationshipTo(newNode, DNRelationshipType.MULTI_VALUED);
                    rel.setProperty(Neo4jStoreManager.RELATIONSHIP_FIELD_NAME, mmd.getName());
                    rel.setProperty(Neo4jStoreManager.RELATIONSHIP_INDEX_NAME, index);
                    if (RelationType.isBidirectional(relationType))
                    {
                        AbstractMemberMetaData[] relMmds = mmd.getRelatedMemberMetaData(clr);
                        rel.setProperty(Neo4jStoreManager.RELATIONSHIP_FIELD_NAME_NONOWNER, relMmds[0].getName());
                    }
                    index++;
                }
            }
        }
        else if (mmd.hasMap())
        {
            Map map = (Map)value;
            if (!mmd.getMap().keyIsPersistent() && mmd.getMap().valueIsPersistent())
            {
                List<Node> relNodes = new ArrayList<Node>();
                List relKeyValues = new ArrayList();
                if (map != null)
                {
                    // Reachability : Persist any objects that are not yet persistent, gathering Node objects
                    Iterator<Map.Entry> mapEntryIter = map.entrySet().iterator();
                    while (mapEntryIter.hasNext())
                    {
                        if (mmd.getMap().isSerializedValue())
                        {
                            throw new NucleusUserException("Don't currently support serialised map values at " + mmd.getFullFieldName());
                        }

                        Map.Entry entry = mapEntryIter.next();
                        Object key = entry.getKey();
                        Object val = entry.getValue();
                        if (val != null)
                        {
                            Object valPC = ec.persistObjectInternal(val, null, -1, -1);
                            ObjectProvider relatedOP = ec.findObjectProvider(valPC);
                            Node relatedNode = (Node)Neo4jUtils.getPropertyContainerForObjectProvider(propObj.getGraphDatabase(), relatedOP);
                            relNodes.add(relatedNode);
                            relKeyValues.add(Neo4jUtils.getStoredValueForField(ec, mmd, key, FieldRole.ROLE_MAP_KEY));
                        }
                        else
                        {
                            throw new NucleusUserException("Dont currently support having null values in maps : " + mmd.getFullFieldName());
                        }
                    }
                }

                if (relationType != RelationType.ONE_TO_MANY_BI && relationType != RelationType.ONE_TO_MANY_UNI &&
                    !(relationType == RelationType.MANY_TO_MANY_BI && mmd.getMappedBy() == null))
                {
                    // We only store relations when we are the owner
                    return;
                }

                if (insert)
                {
                    // Insert of the map, so create Relationship owner-value (with key as property in some cases)
                    Iterator relKeyIter = relKeyValues.iterator();
                    for (Node newNode : relNodes)
                    {
                        Relationship rel = node.createRelationshipTo(newNode, DNRelationshipType.MULTI_VALUED);
                        rel.setProperty(Neo4jStoreManager.RELATIONSHIP_FIELD_NAME, mmd.getName());
                        if (mmd.getKeyMetaData() != null && mmd.getKeyMetaData().getMappedBy() != null)
                        {
                            // Do nothing, key stored in field in value
                        }
                        else
                        {
                            // Store key in property on Relationship
                            Object relKeyVal = relKeyIter.next();
                            rel.setProperty(Neo4jStoreManager.RELATIONSHIP_MAP_KEY_VALUE, relKeyVal);
                        }
                        if (RelationType.isBidirectional(relationType))
                        {
                            AbstractMemberMetaData[] relMmds = mmd.getRelatedMemberMetaData(clr);
                            rel.setProperty(Neo4jStoreManager.RELATIONSHIP_FIELD_NAME_NONOWNER, relMmds[0].getName());
                        }
                    }
                }
                else
                {
                    // Update of the map so remove existing Relationships and create new
                    // TODO Handle better detecting which are still present and which new/updated
                    deleteRelationshipsForMultivaluedMember(node, mmd);

                    Iterator relKeyIter = relKeyValues.iterator();
                    for (Node newNode : relNodes)
                    {
                        Relationship rel = node.createRelationshipTo(newNode, DNRelationshipType.MULTI_VALUED);
                        rel.setProperty(Neo4jStoreManager.RELATIONSHIP_FIELD_NAME, mmd.getName());
                        if (mmd.getKeyMetaData() != null && mmd.getKeyMetaData().getMappedBy() != null)
                        {
                            // Do nothing, key stored in field in value
                        }
                        else
                        {
                            // Store key in property on Relationship
                            Object relKeyVal = relKeyIter.next();
                            rel.setProperty(Neo4jStoreManager.RELATIONSHIP_MAP_KEY_VALUE, relKeyVal);
                        }
                        if (RelationType.isBidirectional(relationType))
                        {
                            AbstractMemberMetaData[] relMmds = mmd.getRelatedMemberMetaData(clr);
                            rel.setProperty(Neo4jStoreManager.RELATIONSHIP_FIELD_NAME_NONOWNER, relMmds[0].getName());
                        }
                    }
                }
            }
            else if (mmd.getMap().keyIsPersistent() && !mmd.getMap().valueIsPersistent())
            {
                List<Node> relNodes = new ArrayList<Node>();
                List relValValues = new ArrayList();
                if (map != null)
                {
                    // Reachability : Persist any objects that are not yet persistent, gathering Node objects
                    Iterator<Map.Entry> mapEntryIter = map.entrySet().iterator();
                    while (mapEntryIter.hasNext())
                    {
                        if (mmd.getMap().isSerializedKey())
                        {
                            throw new NucleusUserException("Don't currently support serialised map keys at " + mmd.getFullFieldName());
                        }

                        Map.Entry entry = mapEntryIter.next();
                        Object key = entry.getKey();
                        Object val = entry.getValue();
                        if (val != null)
                        {
                            Object keyPC = ec.persistObjectInternal(key, null, -1, -1);
                            ObjectProvider relatedOP = ec.findObjectProvider(keyPC);
                            Node relatedNode = (Node)Neo4jUtils.getPropertyContainerForObjectProvider(propObj.getGraphDatabase(), relatedOP);
                            relNodes.add(relatedNode);
                            relValValues.add(Neo4jUtils.getStoredValueForField(ec, mmd, val, FieldRole.ROLE_MAP_VALUE));
                        }
                        else
                        {
                            throw new NucleusUserException("Dont currently support having null keys in maps : " + mmd.getFullFieldName());
                        }
                    }
                }

                if (relationType != RelationType.ONE_TO_MANY_BI && relationType != RelationType.ONE_TO_MANY_UNI &&
                    !(relationType == RelationType.MANY_TO_MANY_BI && mmd.getMappedBy() == null))
                {
                    // We only store relations when we are the owner
                    return;
                }

                if (insert)
                {
                    // Insert of the map, so create Relationship owner-key (with value as property in some cases)
                    Iterator relValIter = relValValues.iterator();
                    for (Node newNode : relNodes)
                    {
                        Relationship rel = node.createRelationshipTo(newNode, DNRelationshipType.MULTI_VALUED);
                        rel.setProperty(Neo4jStoreManager.RELATIONSHIP_FIELD_NAME, mmd.getName());
                        if (mmd.getValueMetaData() != null && mmd.getValueMetaData().getMappedBy() != null)
                        {
                            // Do nothing, value stored in field in key
                        }
                        else
                        {
                            // Store value in property on Relationship
                            Object relValValue = relValIter.next();
                            rel.setProperty(Neo4jStoreManager.RELATIONSHIP_MAP_VAL_VALUE, relValValue);
                        }
                        if (RelationType.isBidirectional(relationType))
                        {
                            AbstractMemberMetaData[] relMmds = mmd.getRelatedMemberMetaData(clr);
                            rel.setProperty(Neo4jStoreManager.RELATIONSHIP_FIELD_NAME_NONOWNER, relMmds[0].getName());
                        }
                    }
                }
                else
                {
                    // Update of the map so remove existing Relationships and create new
                    // TODO Handle better detecting which are still present and which new/updated
                    deleteRelationshipsForMultivaluedMember(node, mmd);

                    Iterator relValIter = relValValues.iterator();
                    for (Node newNode : relNodes)
                    {
                        Relationship rel = node.createRelationshipTo(newNode, DNRelationshipType.MULTI_VALUED);
                        rel.setProperty(Neo4jStoreManager.RELATIONSHIP_FIELD_NAME, mmd.getName());
                        if (mmd.getValueMetaData() != null && mmd.getValueMetaData().getMappedBy() != null)
                        {
                            // Do nothing, value stored in field in key
                        }
                        else
                        {
                            // Store value in property on Relationship
                            Object relValValue = relValIter.next();
                            rel.setProperty(Neo4jStoreManager.RELATIONSHIP_MAP_VAL_VALUE, relValValue);
                        }
                        if (RelationType.isBidirectional(relationType))
                        {
                            AbstractMemberMetaData[] relMmds = mmd.getRelatedMemberMetaData(clr);
                            rel.setProperty(Neo4jStoreManager.RELATIONSHIP_FIELD_NAME_NONOWNER, relMmds[0].getName());
                        }
                    }
                }
            }
            else
            {
                // TODO Persist map<PC,PC>
                throw new NucleusUserException("Don't currently support maps of persistable objects : " + mmd.getFullFieldName());
            }
        }
    }

    /**
     * Convenience method that finds all relationships from the provided owner node and deletes all that
     * are for the specified field.
     * @param ownerNode The owner Node
     * @param mmd Metadata for the member that we are removing relationships for
     */
    private void deleteRelationshipsForMultivaluedMember(Node ownerNode, AbstractMemberMetaData mmd)
    {
        Iterable<Relationship> rels = ownerNode.getRelationships(DNRelationshipType.MULTI_VALUED);
        Iterator<Relationship> relIter = rels.iterator();
        while (relIter.hasNext())
        {
            Relationship rel = relIter.next();
            if (rel.getProperty(Neo4jStoreManager.RELATIONSHIP_FIELD_NAME).equals(mmd.getName()))
            {
                rel.delete();
            }
        }
    }
}