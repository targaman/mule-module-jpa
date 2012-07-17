/**
 * This file was automatically generated by the Mule Development Kit
 */
package org.mule.module.jpa;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mule.api.MuleContext;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.api.annotations.Configurable;
import org.mule.api.annotations.Module;
import org.mule.api.annotations.Processor;
import org.mule.api.annotations.param.Optional;
import org.mule.api.context.MuleContextAware;
import org.mule.api.transaction.Transaction;
import org.mule.module.jpa.command.*;
import org.mule.transaction.TransactionCoordination;
import org.mule.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.persistence.EntityManagerFactory;
import java.util.HashMap;
import java.util.Map;

/**
 * Java Persistence API (JPA) support for Mule.
 *
 * @author John D'Emic <john.demic@mulesoft.com>
 */
@Module(name = "jpa", schemaVersion = "1.0")
public class JPAModule implements MuleContextAware {

    protected transient Log logger = LogFactory.getLog(getClass());

    /**
     * Reference to an <code>EntityManagerFactory</code>
     */
    @Configurable
    EntityManagerFactory entityManagerFactory;

    MuleContext muleContext;

    @PostConstruct
    public void connect() throws Exception {
        muleContext.getTransactionFactoryManager().registerTransactionFactory(JPATransactionFactory.class,
                new JPATransactionFactory(entityManagerFactory));
    }

    public void setMuleContext(MuleContext muleContext) {
        this.muleContext = muleContext;
    }

    /**
     * Execute a JPA query. If the payload of the message is a <code>List</code> or <code>Map</code> then its used
     * as the query parameters.  If neither statement nor namedQuery are set then the payload of the message is assumed
     * to be a <code>CriteriaQuery</code> instance.
     * <p/>
     * {@sample.xml ../../../doc/JPAModule-connector.xml.sample jpa:query}
     *
     * @param message    The <code>MuleMessage</code>.  If the payload is an instance of a <code>List</code>
     *                   or a <code>Map</code> then the payload is used as the query parameters.
     * @param statement  a JPQL statement to execute
     * @param namedQuery a named query to execute
     * @return The query results
     */
    @Processor
    public Object query(MuleMessage message, @Optional String statement, @Optional String namedQuery)
            throws Exception {

        if (logger.isDebugEnabled()) {
            if (StringUtils.isNotBlank(statement)) {
                logger.debug(String.format("Performing query with statement %s and parameters %s", statement,
                        message.getPayload()));
            } else if (StringUtils.isNotBlank(namedQuery)) {
                logger.debug(String.format("Performing query with named query %s and parameters %s", statement,
                        message.getPayload()));
            } else {
                logger.debug("Attempting criteria query with payload: " + message.getPayload());
            }
        }

        Map<String, Object> parameters = new HashMap<String, Object>();

        if (StringUtils.isNotBlank(statement)) {
            parameters.put("statement", statement);
        }

        if (StringUtils.isNotBlank(namedQuery)) {
            parameters.put("namedQuery", statement);
        }

        if (namedQuery != null) {
            parameters.put("namedQuery", namedQuery);
        }

        return perform(message, new Query(), parameters);
    }

    /**
     * Persists the payload of the message.
     * <p/>
     * {@sample.xml ../../../doc/JPAModule-connector.xml.sample jpa:persist}
     *
     * @param message The <code>MuleMessage</code>. This processor assumes the payload is an entity.
     * @return the persisted object
     */
    @Processor
    public Object persist(MuleMessage message) throws Exception {
        logger.debug("Persisting: " + message.getPayloadAsString());
        return perform(message, new Persist(), null);
    }

    /**
     * Merges the payload of the message.
     * <p/>
     * {@sample.xml ../../../doc/JPAModule-connector.xml.sample jpa:merge}
     *
     * @param message The <code>MuleMessage</code>. This processor assumes the payload is an entity.
     * @return the merged object
     */
    @Processor
    public Object merge(MuleMessage message) throws Exception {
        logger.debug("Merging: " + message.getPayloadAsString());
        return perform(message, new Merge(), null);
    }

    /**
     * Looks up an entity using the message's payload as the primary key.
     * <p/>
     * {@sample.xml ../../../doc/JPAModule-connector.xml.sample jpa:find}
     *
     * @param message     The <code>MuleMessage</code>. This processor assumes the payload contains the entity's primary key.
     * @param entityClass The class of the entity to find.
     * @return The entity or null if it isn't found.
     */
    @Processor
    public Object find(MuleMessage message, String entityClass) throws Exception {
        logger.debug(String.format("Finding entity of class: %s with primary key: %s", entityClass,
                message.getPayloadAsString()));
        Map<String, Object> parameters = new HashMap<String, Object>();
        parameters.put("entityClass", entityClass);
        return perform(message, new Find(), parameters);
    }

    /**
     * Detaches the message's payload from the session.
     * <p/>
     * {@sample.xml ../../../doc/JPAModule-connector.xml.sample jpa:detach}
     *
     * @param message The <code>MuleMessage</code>. This processor assumes the payload is an entity.
     * @return the inserted object
     */
    @Processor
    public Object detach(MuleMessage message) throws Exception {
        logger.debug("Detaching: " + message.getPayloadAsString());
        return perform(message, new Detach(), null);
    }

    /**
     * Command pattern implementation for JPA commands. The rationale behind this is to wrap the transaction
     * demarcations around the JPA Commands, to avoid cut and pasting the transaction logic (or trying to use the
     * Transactional annotation.)
     */
    Object perform(MuleMessage message, JPACommand command, Map<String, Object> parameters) throws Exception {

        logger.debug(String.format("Executing JPA command with message: %s, command: %s and parameters: %s",
                message, command, parameters));

        boolean localTransaction = false;

        JPATransaction transaction = getTransactionalResource();

        // If we have a null JPATransaction here then we need to "locally" manage the transaction for this MP.
        if (transaction == null) {
            localTransaction = true;
            transaction = (JPATransaction) new
                    JPATransactionFactory(entityManagerFactory).beginTransaction(muleContext);
        }

        Object result = message.getPayload();

        try {
            result = command.execute(transaction.getEntityManager(), message.getPayload(), parameters);
            if (localTransaction)
                transaction.commit();
        } catch (Exception e) {
            if (localTransaction) {
                transaction.rollback();
            }
            throw e;
        } finally {
            if (localTransaction)
                transaction.doClose();
        }

        return result;
    }

    /**
     * Returns a <code>JPATransaction</code> or null depending on whether or not this module's message processors
     * are running inside a <transactional></transactional> block.  This code will return a <code>JPATransaction</code>
     * if the processors are running inside a <transactional></transactional> block and null if not.  A null
     * value signifies we need to manually create the JPATransaction with the JPATransactionFactory to "locally"
     * manage non-explicit transaction.  This essentially means that each of the processors in this module run in their
     * own transaction unless they are inside a <transactional>block</transactional>.
     */
    @SuppressWarnings({"unchecked"})
    final public <T> T getTransactionalResource() throws MuleException {
        Transaction currentTx = TransactionCoordination.getInstance().getTransaction();
        if (currentTx != null) {
            JPATransactionFactory jpaTransactionFactory = new JPATransactionFactory(entityManagerFactory);

            if (currentTx.hasResource(jpaTransactionFactory)) {
                return (T) currentTx.getResource(jpaTransactionFactory);
            } else {
                Object connectionResource = jpaTransactionFactory.beginTransaction(muleContext);
                if (currentTx.supports(jpaTransactionFactory, connectionResource)) {
                    currentTx.bindResource(jpaTransactionFactory, connectionResource);
                }
                return (T) connectionResource;
            }
        } else {
            return null;
        }
    }

    public void setEntityManagerFactory(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

}
