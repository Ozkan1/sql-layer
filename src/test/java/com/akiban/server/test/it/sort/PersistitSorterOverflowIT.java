/**
 * Copyright (C) 2009-2013 Akiban Technologies, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.akiban.server.test.it.sort;

import com.akiban.ais.model.UserTable;
import com.akiban.qp.operator.API;
import com.akiban.qp.operator.Cursor;
import com.akiban.qp.operator.QueryBindings;
import com.akiban.qp.operator.QueryContext;
import com.akiban.qp.operator.RowCursor;
import com.akiban.qp.persistitadapter.TempVolume;
import com.akiban.qp.persistitadapter.PersistitAdapter;
import com.akiban.qp.persistitadapter.indexcursor.PersistitSorter;
import com.akiban.qp.rowtype.RowType;
import com.akiban.qp.rowtype.Schema;
import com.akiban.qp.util.SchemaCache;
import com.akiban.server.error.PersistitAdapterException;
import com.akiban.server.test.ExpressionGenerators;
import com.akiban.server.test.it.ITBase;
import com.akiban.util.tap.InOutTap;
import com.akiban.util.tap.Tap;
import com.persistit.exception.PersistitIOException;
import com.persistit.exception.VolumeFullException;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class PersistitSorterOverflowIT extends ITBase {
    private static final String SCHEMA = "test";
    private static final String TABLE = "t";
    private static final int ROW_COUNT = 1000;


    @Override
    public Map<String,String> startupConfigProperties() {
        Map<String,String> props = new HashMap<>();
        props.putAll(super.startupConfigProperties());
        props.put("persistit.tmpvolmaxsize","100K");
        return props;
    }

    @Test
    public void tempVolumeFull() {
        boolean caught = false;
        int tid = createTable();
        for(int i = 0; i < 100 && !caught; ++i) {
            loadRows(tid, i * ROW_COUNT);
            try {
                doSort();
            } catch(PersistitAdapterException e) {
                assertEquals("caused by", VolumeFullException.class, e.getCause().getClass());
                caught = true;
            }
            String msg = "has temp state" + (caught ? " after exception" : "");
            assertEquals(msg, false, TempVolume.hasTempState(session()));
        }
        assertEquals("caught exception", true, caught);
    }

    @Test
    public void diskFull() {
        int tid = createTable();
        loadRows(tid, 0);
        assertEquals("has temp state", false, TempVolume.hasTempState(session()));
        TempVolume.setInjectIOException(true);
        try {
            doSort();
        } catch(PersistitAdapterException e) {
            assertEquals("caused by", PersistitIOException.class, e.getCause().getClass());
        } finally {
            TempVolume.setInjectIOException(false);
        }
        assertEquals("has temp state", false, TempVolume.hasTempState(session()));
    }

    private int createTable() {
        return createTable(SCHEMA, TABLE, "id INT NOT NULL PRIMARY KEY, v VARCHAR(32)");
    }

    private void loadRows(int tid, int offset) {
        for(int i = 0; i < ROW_COUNT; ++i) {
            writeRow(tid, offset * ROW_COUNT + i, "test");
        }
    }

    private void doSort() {
        InOutTap tap = Tap.createTimer("test");
        Schema schema = SchemaCache.globalSchema(ddl().getAIS(session()));
        PersistitAdapter adapter = persistitAdapter(schema);

        UserTable userTable = getUserTable(SCHEMA, TABLE);
        RowType rowType = schema.userTableRowType(userTable);
        API.Ordering ordering = API.ordering();
        ordering.append(ExpressionGenerators.field(rowType, 1), true);

        QueryContext context = queryContext(adapter);
        QueryBindings bindings = context.createBindings();
        Cursor inputCursor = API.cursor(
                API.groupScan_Default(userTable.getGroup()),
                context, bindings
        );

        inputCursor.openTopLevel();
        try {
            PersistitSorter sorter = new PersistitSorter(context, bindings, inputCursor, rowType, ordering, API.SortOption.PRESERVE_DUPLICATES, tap);
            RowCursor sortedCursor = sorter.sort();
            sortedCursor.open();
            try {
                while(sortedCursor.next() != null) {
                    // None
                }
            } finally {
                sortedCursor.close();
            }
        } finally {
            inputCursor.closeTopLevel();
        }
    }
}
