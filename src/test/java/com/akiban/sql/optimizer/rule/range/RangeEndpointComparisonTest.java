/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.sql.optimizer.rule.range;

import com.akiban.junit.NamedParameterizedRunner;
import com.akiban.junit.Parameterization;
import com.akiban.junit.ParameterizationBuilder;
import com.akiban.server.types.AkType;
import com.akiban.sql.optimizer.plan.ConstantExpression;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collection;

import static com.akiban.sql.optimizer.rule.range.ComparisonResult.*;
import static org.junit.Assert.assertEquals;

@RunWith(NamedParameterizedRunner.class)
public final class RangeEndpointComparisonTest {
    @NamedParameterizedRunner.TestParameters
    public static Collection<Parameterization> params() {
        ParameterizationBuilder pb = new ParameterizationBuilder();

        // nulls vs nulls
        param(pb,  RangeEndpoint.NULL_INCLUSIVE, EQ,  RangeEndpoint.NULL_INCLUSIVE);
        param(pb,  RangeEndpoint.NULL_INCLUSIVE, LT_BARELY,  RangeEndpoint.NULL_EXCLUSIVE);
        param(pb,  RangeEndpoint.NULL_EXCLUSIVE, EQ,  RangeEndpoint.NULL_EXCLUSIVE);

        // nulls vs "normal" values
        param(pb,  RangeEndpoint.NULL_INCLUSIVE, LT, inclusive(AARDVARK));
        param(pb,  RangeEndpoint.NULL_INCLUSIVE, LT, exclusive(AARDVARK));
        param(pb,  RangeEndpoint.NULL_EXCLUSIVE, LT, inclusive(AARDVARK));
        param(pb,  RangeEndpoint.NULL_EXCLUSIVE, LT, exclusive(AARDVARK));

        // nulls vs wild
        param(pb,  RangeEndpoint.NULL_INCLUSIVE, LT, RangeEndpoint.UPPER_WILD);
        param(pb,  RangeEndpoint.NULL_EXCLUSIVE, LT, RangeEndpoint.UPPER_WILD);

        // normal values vs same values
        param(pb, inclusive(AARDVARK), EQ, inclusive(AARDVARK));
        param(pb, inclusive(AARDVARK), LT_BARELY, exclusive(AARDVARK));

        // normal values vs comparable values
        param(pb, inclusive(AARDVARK), LT, inclusive(CAT));
        param(pb, inclusive(AARDVARK), LT, exclusive(CAT));
        param(pb, exclusive(AARDVARK), LT, inclusive(CAT));
        param(pb, exclusive(AARDVARK), LT, exclusive(CAT));

        // normal values vs wild
        param(pb, inclusive(AARDVARK), LT, RangeEndpoint.UPPER_WILD);
        param(pb, exclusive(AARDVARK), LT, RangeEndpoint.UPPER_WILD);

        // wild vs wild
        param(pb, RangeEndpoint.UPPER_WILD, EQ, RangeEndpoint.UPPER_WILD);

        // incomparable types
        param(pb, inclusive(TWO), INVALID, inclusive(AARDVARK));
        param(pb, inclusive(TWO), INVALID, exclusive(AARDVARK));
        param(pb, exclusive(TWO), INVALID, inclusive(AARDVARK));
        param(pb, exclusive(TWO), INVALID, exclusive(AARDVARK));

        return pb.asList();
    }

    private static void param(ParameterizationBuilder pb,
                              RangeEndpoint one, ComparisonResult expected, RangeEndpoint two)
    {
        String name = one + " " + expected.describe() + " " + two;
        pb.add(name, one, two, expected);
        // test reflectivity
        final ComparisonResult flippedExpected;
        switch (expected) {
        case LT:        flippedExpected = GT;           break;
        case LT_BARELY: flippedExpected = GT_BARELY;    break;
        case GT:        flippedExpected = LT;           break;
        case GT_BARELY: flippedExpected = LT_BARELY;    break;
        default:        flippedExpected = expected;     break;
        }
        String flippedName = two + " " + flippedExpected.describe() + " " + one;
        if (!flippedName.equals(name)) { // e.g. we don't need to reflect inclusive("A") == inclusive("A")
            pb.add(flippedName, two, one, flippedExpected);
        }
    }

    private static RangeEndpoint inclusive(String value) {
        return RangeEndpoint.inclusive(new ConstantExpression(value, AkType.VARCHAR));
    }

    private static RangeEndpoint exclusive(String value) {
        return RangeEndpoint.exclusive(new ConstantExpression(value, AkType.VARCHAR));
    }

    private static RangeEndpoint inclusive(long value) {
        return RangeEndpoint.inclusive(new ConstantExpression(value, AkType.LONG));
    }
    private static RangeEndpoint exclusive(long value) {
        return RangeEndpoint.exclusive(new ConstantExpression(value, AkType.LONG));
    }

    private static String AARDVARK = "aardvark";
    private static String CAT = "cat";
    private static long TWO = 2;

    @Test
    public void compare() {
        assertEquals(expected, one.comparePreciselyTo(two));
    }

    public RangeEndpointComparisonTest(RangeEndpoint one, RangeEndpoint two, ComparisonResult expected) {
        this.one = one;
        this.two = two;
        this.expected = expected;
    }

    private final RangeEndpoint one;
    private final RangeEndpoint two;
    private final ComparisonResult expected;
}
