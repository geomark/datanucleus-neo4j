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
package org.datanucleus.store.neo4j.query;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.datanucleus.ExecutionContext;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.EmbeddedMetaData;
import org.datanucleus.metadata.RelationType;
import org.datanucleus.query.compiler.CompilationComponent;
import org.datanucleus.query.compiler.QueryCompilation;
import org.datanucleus.query.evaluator.AbstractExpressionEvaluator;
import org.datanucleus.query.expression.Expression;
import org.datanucleus.query.expression.InvokeExpression;
import org.datanucleus.query.expression.Literal;
import org.datanucleus.query.expression.OrderExpression;
import org.datanucleus.query.expression.ParameterExpression;
import org.datanucleus.query.expression.PrimaryExpression;
import org.datanucleus.store.neo4j.Neo4jUtils;
import org.datanucleus.store.neo4j.query.expression.Neo4jAggregateExpression;
import org.datanucleus.store.neo4j.query.expression.Neo4jBooleanExpression;
import org.datanucleus.store.neo4j.query.expression.Neo4jExpression;
import org.datanucleus.store.neo4j.query.expression.Neo4jFieldExpression;
import org.datanucleus.store.neo4j.query.expression.Neo4jLiteral;
import org.datanucleus.store.query.Query;
import org.datanucleus.store.schema.naming.ColumnType;
import org.datanucleus.store.types.SCO;
import org.datanucleus.store.types.converters.TypeConverter;
import org.datanucleus.util.NucleusLogger;
import org.datanucleus.util.StringUtils;

/**
 * Mapper to convert a generic query compilation into components for a Neo4j Cypher query.
 */
public class QueryToCypherMapper extends AbstractExpressionEvaluator
{
    final ExecutionContext ec;

    final AbstractClassMetaData candidateCmd;

    final Query query;

    final QueryCompilation compilation;

    /** Input parameter values, keyed by the parameter name. Will be null if compiled pre-execution. */
    final Map parameters;

    /** Positional parameter that we are up to (-1 implies not being used). */
    int positionalParamNumber = -1;

    /** State variable for the component being compiled. */
    CompilationComponent compileComponent;

    String filterText = null;

    boolean filterComplete = true;

    String resultText = null;

    boolean resultComplete = true;

    String orderText = null;

    boolean orderComplete = true;

    boolean precompilable = true;

    /** Stack of neo4j expressions, used for forming the Cypher query component(s). */
    Stack<Neo4jExpression> stack = new Stack();

    public QueryToCypherMapper(QueryCompilation compilation, Map parameters, AbstractClassMetaData cmd,
            ExecutionContext ec, Query q)
    {
        this.ec = ec;
        this.query = q;
        this.compilation = compilation;
        this.parameters = parameters;
        this.candidateCmd = cmd;
    }

    /**
     * Method to compile the query for use as a Cypher query in the datastore.
     * This takes in the datastore compilation and updates its contents with the cypher query info
     * @param neo4jCompilation Datastore compilation
     */
    public void compile(Neo4jQueryCompilation neo4jCompilation)
    {
        compileFilter();
        compileResult();
        compileOrder();

        neo4jCompilation.setPrecompilable(precompilable);

        // Set which parts of the query were compilable for processing in the datastore
        neo4jCompilation.setFilterComplete(filterComplete);
        neo4jCompilation.setResultComplete(resultComplete);
        neo4jCompilation.setOrderComplete(orderComplete);
        Long rangeFrom = null;
        Long rangeTo = null;
        if (filterComplete)
        {
            if (orderComplete)
            {
                rangeFrom = (query.getRangeFromIncl() > 0 ? query.getRangeFromIncl() : null);
                rangeTo = (query.getRangeToExcl() != Long.MAX_VALUE ? query.getRangeToExcl() : null);
                if (rangeFrom != null || rangeTo != null)
                {
                    neo4jCompilation.setRangeComplete(true);
                }
            }
        }

        // Generate the Cypher text (as far as is possible)
        String cypherText = Neo4jUtils.getCypherTextForQuery(ec, candidateCmd, compilation.getCandidateAlias(), 
            query.isSubclasses(), filterText, (resultComplete ? resultText : null), orderText, rangeFrom, rangeTo);
        neo4jCompilation.setCypherText(cypherText);
    }

    /**
     * Method to compile the WHERE clause of the query
     */
    protected void compileFilter()
    {
        if (compilation.getExprFilter() != null)
        {
            compileComponent = CompilationComponent.FILTER;

            try
            {
                compilation.getExprFilter().evaluate(this);
                Neo4jExpression neoExpr = stack.pop();
                if (!(neoExpr instanceof Neo4jBooleanExpression))
                {
                    NucleusLogger.QUERY.error("Invalid compilation : filter compiled to " + neoExpr);
                    filterComplete = false;
                }
                else
                {
                    filterText = ((Neo4jBooleanExpression) neoExpr).getCypherText();
                }
            }
            catch (Exception e)
            {
                // Impossible to compile all to run in the datastore, so just exit
                if (NucleusLogger.QUERY.isDebugEnabled())
                {
                    NucleusLogger.QUERY.debug("Compilation of filter to be evaluated completely in-datastore was impossible : " + e.getMessage());
                }
                filterComplete = false;
            }

            compileComponent = null;
        }
    }

    /**
     * Method to compile the ORDER clause of the query
     */
    protected void compileOrder()
    {
        if (compilation.getExprOrdering() != null)
        {
            compileComponent = CompilationComponent.ORDERING;

            try
            {
                StringBuffer orderStr = new StringBuffer();
                Expression[] orderingExpr = compilation.getExprOrdering();
                for (int i=0;i<orderingExpr.length;i++)
                {
                    OrderExpression orderExpr = (OrderExpression)orderingExpr[i];
                    orderExpr.evaluate(this);
                    Neo4jExpression neoExpr = stack.pop();
                    orderStr.append(neoExpr.getCypherText());
                    String orderDir = orderExpr.getSortOrder();
                    if (orderDir.equalsIgnoreCase("descending"))
                    {
                        orderStr.append(" DESC");
                    }
                    if (i < orderingExpr.length-1)
                    {
                        orderStr.append(",");
                    }
                }
                orderText = orderStr.toString();
            }
            catch (Exception e)
            {
                // Impossible to compile all to run in the datastore, so just exit
                if (NucleusLogger.QUERY.isDebugEnabled())
                {
                    NucleusLogger.QUERY.debug("Compilation of ordering to be evaluated completely in-datastore was impossible : " + e.getMessage());
                }
                orderComplete = false;
            }

            compileComponent = null;
        }
    }

    /**
     * Method to compile the result clause of the query
     */
    protected void compileResult()
    {
        if (compilation.getExprResult() != null)
        {
            compileComponent = CompilationComponent.RESULT;

            // Select any result expressions
            resultComplete = true;
            StringBuffer str = new StringBuffer();
            try
            {
                Expression[] resultExprs = compilation.getExprResult();
                int i = 0;
                for (Expression expr :  resultExprs)
                {
                    Neo4jExpression neo4jExpr = null;
                    if (expr instanceof PrimaryExpression)
                    {
                        PrimaryExpression primExpr = (PrimaryExpression)expr;
                        processPrimaryExpression(primExpr);
                        neo4jExpr = stack.pop();
                        str.append(neo4jExpr.getCypherText());
                    }
                    else if (expr instanceof Literal)
                    {
                        processLiteral((Literal)expr);
                        neo4jExpr = stack.pop();
                        str.append(neo4jExpr.getCypherText());
                    }
                    else if (expr instanceof ParameterExpression)
                    {
                        processParameterExpression((ParameterExpression)expr);
                        neo4jExpr = stack.pop();
                        str.append(neo4jExpr.getCypherText());
                    }
                    else if (expr instanceof InvokeExpression)
                    {
                        InvokeExpression invokeExpr = (InvokeExpression)expr;
                        if (invokeExpr.getLeft() == null)
                        {
                            List<Expression> argExprs = invokeExpr.getArguments();
                            if (argExprs == null || argExprs.size() != 1)
                            {
                                throw new NucleusUserException("Invalid number of arguments to MAX");
                            }

                            Expression argExpr = argExprs.get(0);
                            if (argExpr instanceof PrimaryExpression)
                            {
                                processPrimaryExpression((PrimaryExpression)argExpr);
                            }
                            else
                            {
                                throw new NucleusUserException("Invocation of static method " + 
                                    invokeExpr.getOperation() +" with arg of type " + argExpr.getClass().getName() +
                                    " not supported in-datastore");
                            }

                            Neo4jExpression aggrArgExpr = stack.pop();
                            if (invokeExpr.getOperation().equalsIgnoreCase("MAX") ||
                                invokeExpr.getOperation().equalsIgnoreCase("MIN") ||
                                invokeExpr.getOperation().equalsIgnoreCase("SUM") ||
                                invokeExpr.getOperation().equalsIgnoreCase("AVG") ||
                                invokeExpr.getOperation().equalsIgnoreCase("COUNT"))
                            {
                                Neo4jExpression aggExpr = new Neo4jAggregateExpression(invokeExpr.getOperation(), aggrArgExpr);
                                str.append(aggExpr.getCypherText());
                            }
                            else
                            {
                                throw new NucleusUserException("Invocation of static method " + 
                                    invokeExpr.getOperation() +" not supported in-datastore");
                            }
                        }
                    }
                    else
                    {
                        NucleusLogger.GENERAL.info("Query result expression " + expr + 
                            " not supported via Cypher so will be processed in-memory");
                        resultComplete = false;
                        break;
                    }
                    if (i < resultExprs.length-1)
                    {
                        str.append(",");
                    }
                    i++;
                }
                resultText = str.toString();
            }
            catch (Exception e)
            {
                NucleusLogger.GENERAL.info("Query result clause " + StringUtils.objectArrayToString(compilation.getExprResult()) + 
                    " not totally supported via Cypher so will be processed in-memory");
                resultComplete = false;
            }

            // TODO Handle distinct
            compileComponent = null;
        }
    }

    /* (non-Javadoc)
     * @see org.datanucleus.query.evaluator.AbstractExpressionEvaluator#processAndExpression(org.datanucleus.query.expression.Expression)
     */
    @Override
    protected Object processAndExpression(Expression expr)
    {
        Neo4jBooleanExpression right = (Neo4jBooleanExpression) stack.pop();
        Neo4jBooleanExpression left = (Neo4jBooleanExpression) stack.pop();
        Neo4jBooleanExpression andExpr = new Neo4jBooleanExpression(left, right, Expression.OP_AND);
        stack.push(andExpr);
        return andExpr;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.query.evaluator.AbstractExpressionEvaluator#processOrExpression(org.datanucleus.query.expression.Expression)
     */
    @Override
    protected Object processOrExpression(Expression expr)
    {
        Neo4jBooleanExpression right = (Neo4jBooleanExpression) stack.pop();
        Neo4jBooleanExpression left = (Neo4jBooleanExpression) stack.pop();
        Neo4jBooleanExpression andExpr = new Neo4jBooleanExpression(left, right, Expression.OP_OR);
        stack.push(andExpr);
        return andExpr;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.query.evaluator.AbstractExpressionEvaluator#processEqExpression(org.datanucleus.query.expression.Expression)
     */
    @Override
    protected Object processEqExpression(Expression expr)
    {
        Object right = stack.pop();
        Object left = stack.pop();
        if (left instanceof Neo4jLiteral && right instanceof Neo4jFieldExpression)
        {
            String field = ((Neo4jFieldExpression)right).getFieldName();
            Object value = ((Neo4jLiteral)left).getValue();
            Neo4jExpression neo4jExpr = new Neo4jBooleanExpression(field, value, Expression.OP_EQ);
            stack.push(neo4jExpr);
            return neo4jExpr;
        }
        else if (left instanceof Neo4jFieldExpression && right instanceof Neo4jLiteral)
        {
            String field = ((Neo4jFieldExpression)left).getFieldName();
            Object value = ((Neo4jLiteral)right).getValue();
            Neo4jExpression neo4jExpr = new Neo4jBooleanExpression(field, value, Expression.OP_EQ);
            stack.push(neo4jExpr);
            return neo4jExpr;
        }

        // TODO Auto-generated method stub
        return super.processEqExpression(expr);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.query.evaluator.AbstractExpressionEvaluator#processNoteqExpression(org.datanucleus.query.expression.Expression)
     */
    @Override
    protected Object processNoteqExpression(Expression expr)
    {
        Object right = stack.pop();
        Object left = stack.pop();
        if (left instanceof Neo4jLiteral && right instanceof Neo4jFieldExpression)
        {
            String field = ((Neo4jFieldExpression)right).getFieldName();
            Object value = ((Neo4jLiteral)left).getValue();
            Neo4jExpression neo4jExpr = new Neo4jBooleanExpression(field, value, Expression.OP_NOTEQ);
            stack.push(neo4jExpr);
            return neo4jExpr;
        }
        else if (left instanceof Neo4jFieldExpression && right instanceof Neo4jLiteral)
        {
            String field = ((Neo4jFieldExpression)left).getFieldName();
            Object value = ((Neo4jLiteral)right).getValue();
            Neo4jExpression neo4jExpr = new Neo4jBooleanExpression(field, value, Expression.OP_NOTEQ);
            stack.push(neo4jExpr);
            return neo4jExpr;
        }

        // TODO Auto-generated method stub
        return super.processEqExpression(expr);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.query.evaluator.AbstractExpressionEvaluator#processGtExpression(org.datanucleus.query.expression.Expression)
     */
    @Override
    protected Object processGtExpression(Expression expr)
    {
        Object right = stack.pop();
        Object left = stack.pop();
        if (left instanceof Neo4jLiteral && right instanceof Neo4jFieldExpression)
        {
            String field = ((Neo4jFieldExpression)right).getFieldName();
            Object value = ((Neo4jLiteral)left).getValue();
            Neo4jExpression neo4jExpr = new Neo4jBooleanExpression(field, value, Expression.OP_LTEQ);
            stack.push(neo4jExpr);
            return neo4jExpr;
        }
        else if (left instanceof Neo4jFieldExpression && right instanceof Neo4jLiteral)
        {
            String field = ((Neo4jFieldExpression)left).getFieldName();
            Object value = ((Neo4jLiteral)right).getValue();
            Neo4jExpression neo4jExpr = new Neo4jBooleanExpression(field, value, Expression.OP_GT);
            stack.push(neo4jExpr);
            return neo4jExpr;
        }

        // TODO Auto-generated method stub
        return super.processEqExpression(expr);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.query.evaluator.AbstractExpressionEvaluator#processLtExpression(org.datanucleus.query.expression.Expression)
     */
    @Override
    protected Object processLtExpression(Expression expr)
    {
        Object right = stack.pop();
        Object left = stack.pop();
        if (left instanceof Neo4jLiteral && right instanceof Neo4jFieldExpression)
        {
            String field = ((Neo4jFieldExpression)right).getFieldName();
            Object value = ((Neo4jLiteral)left).getValue();
            Neo4jExpression neo4jExpr = new Neo4jBooleanExpression(field, value, Expression.OP_GTEQ);
            stack.push(neo4jExpr);
            return neo4jExpr;
        }
        else if (left instanceof Neo4jFieldExpression && right instanceof Neo4jLiteral)
        {
            String field = ((Neo4jFieldExpression)left).getFieldName();
            Object value = ((Neo4jLiteral)right).getValue();
            Neo4jExpression neo4jExpr = new Neo4jBooleanExpression(field, value, Expression.OP_LT);
            stack.push(neo4jExpr);
            return neo4jExpr;
        }

        // TODO Auto-generated method stub
        return super.processEqExpression(expr);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.query.evaluator.AbstractExpressionEvaluator#processGteqExpression(org.datanucleus.query.expression.Expression)
     */
    @Override
    protected Object processGteqExpression(Expression expr)
    {
        Object right = stack.pop();
        Object left = stack.pop();
        if (left instanceof Neo4jLiteral && right instanceof Neo4jFieldExpression)
        {
            String field = ((Neo4jFieldExpression)right).getFieldName();
            Object value = ((Neo4jLiteral)left).getValue();
            Neo4jExpression neo4jExpr = new Neo4jBooleanExpression(field, value, Expression.OP_LT);
            stack.push(neo4jExpr);
            return neo4jExpr;
        }
        else if (left instanceof Neo4jFieldExpression && right instanceof Neo4jLiteral)
        {
            String field = ((Neo4jFieldExpression)left).getFieldName();
            Object value = ((Neo4jLiteral)right).getValue();
            Neo4jExpression neo4jExpr = new Neo4jBooleanExpression(field, value, Expression.OP_GTEQ);
            stack.push(neo4jExpr);
            return neo4jExpr;
        }

        // TODO Auto-generated method stub
        return super.processEqExpression(expr);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.query.evaluator.AbstractExpressionEvaluator#processLteqExpression(org.datanucleus.query.expression.Expression)
     */
    @Override
    protected Object processLteqExpression(Expression expr)
    {
        Object right = stack.pop();
        Object left = stack.pop();
        if (left instanceof Neo4jLiteral && right instanceof Neo4jFieldExpression)
        {
            String field = ((Neo4jFieldExpression)right).getFieldName();
            Object value = ((Neo4jLiteral)left).getValue();
            Neo4jExpression neo4jExpr = new Neo4jBooleanExpression(field, value, Expression.OP_GT);
            stack.push(neo4jExpr);
            return neo4jExpr;
        }
        else if (left instanceof Neo4jFieldExpression && right instanceof Neo4jLiteral)
        {
            String field = ((Neo4jFieldExpression)left).getFieldName();
            Object value = ((Neo4jLiteral)right).getValue();
            Neo4jExpression neo4jExpr = new Neo4jBooleanExpression(field, value, Expression.OP_LTEQ);
            stack.push(neo4jExpr);
            return neo4jExpr;
        }

        // TODO Auto-generated method stub
        return super.processEqExpression(expr);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.query.evaluator.AbstractExpressionEvaluator#processNotExpression(org.datanucleus.query.expression.Expression)
     */
    @Override
    protected Object processNotExpression(Expression expr)
    {
        Object theExpr = stack.pop();
        if (theExpr instanceof Neo4jBooleanExpression)
        {
            Neo4jExpression neo4jExpr = new Neo4jBooleanExpression((Neo4jBooleanExpression) theExpr, Expression.OP_NOT);
            stack.push(neo4jExpr);
            return neo4jExpr;
        }

        return super.processNotExpression(expr);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.query.evaluator.AbstractExpressionEvaluator#processParameterExpression(org.datanucleus.query.expression.ParameterExpression)
     */
    @Override
    protected Object processParameterExpression(ParameterExpression expr)
    {
        // Extract the parameter value (if set)
        Object paramValue = null;
        boolean paramValueSet = false;
        if (parameters != null && !parameters.isEmpty())
        {
            // Check if the parameter has a value
            if (parameters.containsKey(expr.getId()))
            {
                // Named parameter
                paramValue = parameters.get(expr.getId());
                paramValueSet = true;
            }
            else if (parameters.containsKey(expr.getId()))
            {
                // Positional parameter, but already encountered
                paramValue = parameters.get(expr.getId());
                paramValueSet = true;
            }
            else
            {
                // Positional parameter, not yet encountered
                int position = positionalParamNumber;
                if (positionalParamNumber < 0)
                {
                    position = 0;
                }
                if (parameters.containsKey(Integer.valueOf(position)))
                {
                    paramValue = parameters.get(Integer.valueOf(position));
                    paramValueSet = true;
                    positionalParamNumber = position+1;
                }
            }
        }

        // TODO Change this to use Neo4jUtils.getStoredValueForField
        if (paramValueSet)
        {
            if (paramValue instanceof Number)
            {
                Neo4jLiteral lit = new Neo4jLiteral(paramValue);
                stack.push(lit);
                precompilable = false;
                return lit;
            }
            else if (paramValue instanceof String)
            {
                Neo4jLiteral lit = new Neo4jLiteral(paramValue);
                stack.push(lit);
                precompilable = false;
                return lit;
            }
            else if (paramValue instanceof Character)
            {
                Neo4jLiteral lit = new Neo4jLiteral("" + paramValue);
                stack.push(lit);
                precompilable = false;
                return lit;
            }
            else if (paramValue instanceof Boolean)
            {
                Neo4jLiteral lit = new Neo4jLiteral(paramValue);
                stack.push(lit);
                precompilable = false;
                return lit;
            }
            else if (paramValue instanceof java.util.Date)
            {
                // java.util.Date etc are stored via converter
                Object storedVal = paramValue;
                Class paramType = paramValue.getClass();
                if (paramValue instanceof SCO)
                {
                    paramType = ((SCO)paramValue).getValue().getClass();
                }
                TypeConverter strConv = ec.getTypeManager().getTypeConverterForType(paramType, String.class);
                TypeConverter longConv = ec.getTypeManager().getTypeConverterForType(paramType, Long.class);
                if (strConv != null)
                {
                    // store as a String
                    storedVal = strConv.toDatastoreType(paramValue);
                }
                else if (longConv != null)
                {
                    // store as a Long
                    storedVal = longConv.toDatastoreType(paramValue);
                }
                Neo4jLiteral lit = new Neo4jLiteral(storedVal);
                stack.push(lit);
                precompilable = false;
                return lit;
            }
            else if (paramValue == null)
            {
                Neo4jLiteral lit = new Neo4jLiteral(null);
                stack.push(lit);
                precompilable = false;
                return lit;
            }
            else
            {
                NucleusLogger.QUERY.info("Dont currently support parameter values of type " + paramValue.getClass().getName());
                // TODO Support other parameter value types
            }
        }
        else
        {
            precompilable = false;
            throw new NucleusException("Parameter " + expr + " is not currently set, so cannot complete the compilation");
        }

        return super.processParameterExpression(expr);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.query.evaluator.AbstractExpressionEvaluator#processPrimaryExpression(org.datanucleus.query.expression.PrimaryExpression)
     */
    @Override
    protected Object processPrimaryExpression(PrimaryExpression expr)
    {
        Expression left = expr.getLeft();
        if (left == null)
        {
            if (expr.getId().equals(compilation.getCandidateAlias()))
            {
                // Special case of the candidate
                Neo4jFieldExpression fieldExpr = new Neo4jFieldExpression(compilation.getCandidateAlias());
                stack.push(fieldExpr);
                return fieldExpr;
            }

            String fieldName = getFieldNameForPrimary(expr);
            if (fieldName == null)
            {
                if (compileComponent == CompilationComponent.FILTER)
                {
                    filterComplete = false;
                }
                NucleusLogger.QUERY.debug(">> Primary " + expr +
                    " is not stored in this Neo4j type, so unexecutable in datastore");
            }
            else
            {
                // Assume all fields are prefixed by the candidate alias!
                Neo4jFieldExpression fieldExpr = new Neo4jFieldExpression(compilation.getCandidateAlias() + "." + fieldName);
                stack.push(fieldExpr);
                return fieldExpr;
            }
        }

        // TODO Auto-generated method stub
        return super.processPrimaryExpression(expr);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.query.evaluator.AbstractExpressionEvaluator#processLiteral(org.datanucleus.query.expression.Literal)
     */
    @Override
    protected Object processLiteral(Literal expr)
    {
        Object litValue = expr.getLiteral();
        if (litValue instanceof BigDecimal)
        {
            // MongoDB can't cope with BigDecimal, so give it a Double
            Neo4jLiteral lit = new Neo4jLiteral(((BigDecimal)litValue).doubleValue());
            stack.push(lit);
            return lit;
        }
        else if (litValue instanceof Number)
        {
            Neo4jLiteral lit = new Neo4jLiteral(litValue);
            stack.push(lit);
            return lit;
        }
        else if (litValue instanceof String)
        {
            Neo4jLiteral lit = new Neo4jLiteral(litValue);
            stack.push(lit);
            return lit;
        }
        else if (litValue instanceof Boolean)
        {
            Neo4jLiteral lit = new Neo4jLiteral(litValue);
            stack.push(lit);
            return lit;
        }
        else if (litValue == null)
        {
            Neo4jLiteral lit = new Neo4jLiteral(null);
            stack.push(lit);
            return lit;
        }

        return super.processLiteral(expr);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.query.evaluator.AbstractExpressionEvaluator#processInvokeExpression(org.datanucleus.query.expression.InvokeExpression)
     */
    @Override
    protected Object processInvokeExpression(InvokeExpression expr)
    {
        // TODO Support some method invocations if there is a Neo4j Cypher equivalent
        // Find object that we invoke on
        Expression invokedExpr = expr.getLeft();
        String operation = expr.getOperation();
        List args = expr.getArguments();
        if (invokedExpr == null)
        {
            // Static method
        }
        else
        {
            // Invoke on other expression
        }
        NucleusLogger.QUERY.debug(">> Dont currently support any method invocation in Neo4j datastore queries : method=" + operation + 
            " args=" + StringUtils.collectionToString(args));
        return super.processInvokeExpression(expr);
    }

    /**
     * Convenience method to return the "field name" in node for this primary.
     * Allows for simple relation fields.
     * @param expr The expression
     * @return The field name for this primary (or null if not resolvable in this node)
     */
    protected String getFieldNameForPrimary(PrimaryExpression expr)
    {
        List<String> tuples = expr.getTuples();
        if (tuples == null || tuples.isEmpty())
        {
            return null;
        }

        AbstractClassMetaData cmd = candidateCmd;
        AbstractMemberMetaData embMmd = null;

        boolean firstTuple = true;
        Iterator<String> iter = tuples.iterator();
        while (iter.hasNext())
        {
            String name = iter.next();
            if (firstTuple && name.equals(compilation.getCandidateAlias()))
            {
                cmd = candidateCmd;
            }
            else
            {
                AbstractMemberMetaData mmd = cmd.getMetaDataForMember(name);
                RelationType relationType = mmd.getRelationType(ec.getClassLoaderResolver());
                if (relationType == RelationType.NONE)
                {
                    if (iter.hasNext())
                    {
                        throw new NucleusUserException("Query has reference to " +
                            StringUtils.collectionToString(tuples) + " yet " + name + " is a non-relation field!");
                    }
                    if (embMmd != null)
                    {
                        // Get property name for field of embedded object
                        return Neo4jUtils.getPropertyNameForEmbeddedField(embMmd, mmd.getAbsoluteFieldNumber());
                    }
                    return ec.getStoreManager().getNamingFactory().getColumnName(mmd, ColumnType.COLUMN);
                }
                else
                {
                    boolean embedded = mmd.isEmbedded();
                    if (!embedded)
                    {
                        // Not explicitly marked as embedded but check whether it is defined in JDO embedded metadata
                        EmbeddedMetaData embmd = mmd.getEmbeddedMetaData();
                        if (embmd == null && embMmd != null)
                        {
                            embmd = embMmd.getEmbeddedMetaData();
                        }
                        if (embmd != null)
                        {
                            AbstractMemberMetaData[] embmmds = embmd.getMemberMetaData();
                            if (embmmds != null)
                            {
                                for (int i=0;i<embmmds.length;i++)
                                {
                                    if (embmmds[i].getName().equals(mmd.getName()))
                                    {
                                        embedded = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    if (embedded)
                    {
                        if (RelationType.isRelationSingleValued(relationType))
                        {
                            cmd = ec.getMetaDataManager().getMetaDataForClass(mmd.getType(), ec.getClassLoaderResolver());
                            if (embMmd != null)
                            {
                                embMmd = embMmd.getEmbeddedMetaData().getMemberMetaData()[mmd.getAbsoluteFieldNumber()];
                            }
                            else
                            {
                                embMmd = mmd;
                            }
                        }
                        else
                        {
                            throw new NucleusUserException(
                                "Do not support the querying of embedded collection/map/array fields : " + 
                                mmd.getFullFieldName());
                        }
                    }
                    else
                    {
                        // Not embedded
                        if (relationType == RelationType.ONE_TO_MANY_UNI || relationType == RelationType.ONE_TO_MANY_BI ||
                            relationType == RelationType.MANY_TO_ONE_UNI || relationType == RelationType.MANY_TO_ONE_BI)
                        {
                            if (!iter.hasNext())
                            {
                                return name;
                            }
                            else
                            {
                                // Need join to another object, not currently supported
                                throw new NucleusUserException("Do not support query joining to related object at " + 
                                        mmd.getFullFieldName() + " in " + StringUtils.collectionToString(tuples));
                            }
                        }
                        else
                        {
                            if (compileComponent == CompilationComponent.FILTER)
                            {
                                filterComplete = false;
                            }

                            NucleusLogger.QUERY.debug("Query has reference to " + 
                                    StringUtils.collectionToString(tuples) + " and " + mmd.getFullFieldName() +
                                    " is not persisted into this object, so unexecutable in the datastore");
                            return null;
                        }
                    }
                }
                firstTuple = false;
            }
        }

        return null;
    }
}