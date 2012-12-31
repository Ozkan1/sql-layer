/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.server.expression.std;

import com.akiban.qp.exec.Plannable;
import com.akiban.qp.operator.QueryContext;
import com.akiban.qp.row.Row;
import com.akiban.server.explain.CompoundExplainer;
import com.akiban.server.explain.ExplainContext;
import com.akiban.server.explain.Label;
import com.akiban.server.explain.PrimitiveExplainer;
import com.akiban.server.explain.Type;
import com.akiban.server.explain.std.ExpressionExplainer;
import com.akiban.server.expression.Expression;
import com.akiban.server.expression.ExpressionEvaluation;
import com.akiban.server.types.AkType;
import com.akiban.server.types.ValueSource;
import java.util.Map;

public final class BoundFieldExpression implements Expression {
    @Override
    public boolean isConstant() {
        return false;
    }

    @Override
    public boolean needsBindings() {
        return true;
    }

    @Override
    public boolean needsRow() {
        return false;
    }

    @Override
    public ExpressionEvaluation evaluation() {
        return new InnerEvaluation(rowBindingPosition, fieldExpression.evaluation());
    }

    @Override
    public AkType valueType() {
        return fieldExpression.valueType();
    }

    // Object interface

    @Override
    public String toString() {
        return String.format("Bound(%d,%s)", rowBindingPosition, fieldExpression.toString());
    }

    // BoundFieldExpression interface

    public BoundFieldExpression(int rowBindingPosition, FieldExpression fieldExpression) {
        this.rowBindingPosition = rowBindingPosition;
        this.fieldExpression = fieldExpression;
    }

    // state
    private final int rowBindingPosition;
    private final FieldExpression fieldExpression;

    @Override
    public String name()
    {
        return "Bound";
    }

    @Override
    public CompoundExplainer getExplainer(ExplainContext context)
    {
        // Extend Field inside, rather than wrapping it.
        CompoundExplainer ex = fieldExpression.getExplainer(context);
        ex.get().remove(Label.NAME); // Want to replace.
        ex.addAttribute(Label.NAME, PrimitiveExplainer.getInstance(name()));
        ex.addAttribute(Label.BINDING_POSITION, PrimitiveExplainer.getInstance(rowBindingPosition));
        if (context.hasExtraInfo(this))
            ex.get().putAll(context.getExtraInfo(this).get());
        return ex;
    }
    
    public boolean nullIsContaminating()
    {
        return true;
    }

    private static class InnerEvaluation implements ExpressionEvaluation {
        @Override
        public void of(Row row) {
        }

        @Override
        public void of(QueryContext context) {
            fieldExpressionEvaluation.of(context.getRow(rowBindingPosition));
        }

        @Override
        public ValueSource eval() {
            return fieldExpressionEvaluation.eval();
        }

        @Override
        public void destroy() {
            fieldExpressionEvaluation.destroy();
        }

        @Override
        public void acquire() {
            fieldExpressionEvaluation.acquire();
        }

        @Override
        public boolean isShared() {
            return fieldExpressionEvaluation.isShared();
        }

        @Override
        public void release() {
            fieldExpressionEvaluation.release();
        }

        private InnerEvaluation(int rowBindingPosition, ExpressionEvaluation fieldExpressionEvaluation) {
            this.rowBindingPosition = rowBindingPosition;
            this.fieldExpressionEvaluation = fieldExpressionEvaluation;
        }

        private final int rowBindingPosition;
        private final ExpressionEvaluation fieldExpressionEvaluation;
    }
}
