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

package com.akiban.qp.persistitadapter;

import com.akiban.ais.model.GroupTable;
import com.akiban.ais.model.UserTable;
import com.akiban.qp.expression.IndexKeyRange;
import com.akiban.qp.physicaloperator.Bindings;
import com.akiban.qp.physicaloperator.GroupCursor;
import com.akiban.qp.row.HKey;
import com.akiban.qp.row.Row;
import com.akiban.qp.row.RowHolder;
import com.akiban.server.InvalidOperationException;
import com.akiban.server.RowDef;
import com.persistit.Exchange;
import com.persistit.Key;
import com.persistit.KeyFilter;
import com.persistit.exception.PersistitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * A PersistitGroupCursor can be used in three ways:
 * 1) Scan the entire group: This occurs when there is no binding before open().
 * 2) For a given hkey, find the row and its descendents: This occurs when rebind(HKey, true) is called.
 * 3) For a given hkey, find the row without its descendents: This occurs when rebind(HKey, false) is called.
 * 4) As an hkey-equivalent index: This occurs when IndexKeyRange is provided via constructor. The index restriction is
 *    on columns of the hkey. Find the qualifying rows and all descendents.
 */


class PersistitGroupCursor implements GroupCursor
{
    // GroupCursor interface

    @Override
    public void rebind(HKey hKey, boolean deep)
    {
        if (exchange != null) {
            throw new IllegalStateException("can't rebind while PersistitGroupCursor is open");
        }
        this.hKey = (PersistitHKey) hKey;
        this.hKeyDeep = deep;
    }


    // Cursor interface

    @Override
    public void open(Bindings bindings)
    {
        assert exchange == null;
        try {
            exchange = adapter.takeExchange(groupTable).clear();
            groupScan =
                hKeyRange == null && hKey == null ? new FullScan() :
                hKeyRange == null && hKeyDeep ? new HKeyAndDescendentsScan(hKey) :
                hKeyRange == null && !hKeyDeep ? new HKeyWithoutDescendentsScan(hKey) :
                hKeyRange.unbounded() ? new FullScan() : new HKeyRangeAndDescendentsScan(hKeyRange, bindings);
        } catch (PersistitException e) {
            throw new PersistitAdapterException(e);
        }
    }

    @Override
    public boolean next()
    {
        try {
            boolean next = exchange != null;
            if (next) {
                groupScan.advance();
                next = exchange != null;
                if (next) {
                    PersistitGroupRow row = unsharedRow().get();
                    row.copyFromExchange(exchange);
                }
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("PersistitGroupCursor: {}", next ? row : null);
            }
            return next;
        } catch (PersistitException e) {
            throw new PersistitAdapterException(e);
        } catch (InvalidOperationException e) {
            throw new PersistitAdapterException(e);
        }
    }

    @Override
    public void close()
    {
        if (exchange != null) {
            adapter.returnExchange(exchange);
            exchange = null;
            groupScan = null;
        }
    }

    @Override
    public Row currentRow()
    {
        return row.get();
    }

    // For use by this package

    PersistitGroupCursor(PersistitAdapter adapter, GroupTable groupTable, IndexKeyRange indexKeyRange)
        throws PersistitException
    {
        this.hKeyRange = indexKeyRange;
        this.adapter = adapter;
        this.groupTable = groupTable;
        this.row = new RowHolder<PersistitGroupRow>(adapter.newGroupRow());
        this.controllingHKey = new Key(adapter.persistit.getDb());
    }

    // For use by this class

    private RowHolder<PersistitGroupRow> unsharedRow()
    {
        if (row.isNull() || row.get().isShared()) {
            row.set(adapter.newGroupRow());
        }
        return row;
    }

    // Class state

    private static final Logger LOG = LoggerFactory.getLogger(PersistitGroupCursor.class);
    private static final int VALUE_BYTES = Integer.MAX_VALUE;

    // Object state

    /*
     * 1) Scan entire group: Initialize exchange to Key.BEFORE and keep going forward, doing a deep traversal,
     *    until there are no more rows.
     *
     * 2) Scan one hkey and descendents: The key is copied to the exchange, to begin the scan, and to controllingHKey
     *    to determine when the scan should end.
     *
     * 3) Scan one hkey without descendents: The key is copied to the exchange.
     *
     * 4) Index range scan: The binding is stored in hKeyRange until the scan begins. The exchange is used with
     *    hKeyRangeFilter to implement the range restriction, alternating with deep traversal. For each
     *    record that hKeyRangeFilter, the current hKey is copied to conrollingHKey, and this is used to
     *    identify descendents, as in (2).
     *
     *  General:
     *  - exchange == null iff this cursor is closed
     */

    Exchange exchange()
    {
        return exchange;
    }

    RowHolder<PersistitGroupRow> currentHeldRow()
    {
        return row;
    }

    PersistitAdapter adapter()
    {
        return adapter;
    }

    private final PersistitAdapter adapter;
    private final GroupTable groupTable;
    private final RowHolder<PersistitGroupRow> row;
    private Exchange exchange;
    private Key controllingHKey;
    private PersistitHKey hKey;
    private boolean hKeyDeep;
    private final IndexKeyRange hKeyRange;
    private GroupScan groupScan;

    // Inner classes

    interface GroupScan
    {
        /**
         * Advance the exchange. Close if this causes the exchange to run out of selected rows.
         *
         * @throws PersistitException
         * @throws InvalidOperationException
         */
        void advance() throws PersistitException, InvalidOperationException;
    }

    private class FullScan implements GroupScan
    {
        @Override
        public void advance() throws PersistitException, InvalidOperationException
        {
            if (!exchange.traverse(direction, true)) {
                close();
            }
        }

        public FullScan() throws PersistitException
        {
            exchange.getKey().append(Key.BEFORE);
            direction = Key.GT;
        }

        private final Key.Direction direction;
    }

    private class HKeyAndDescendentsScan implements GroupScan
    {
        @Override
        public void advance() throws PersistitException, InvalidOperationException
        {
            if (first) {
                if (!exchange.traverse(Key.GTEQ, true)) {
                    close();
                }
                first = false;
            } else {
                if (!exchange.traverse(Key.GT, true) ||
                    exchange.getKey().firstUniqueByteIndex(controllingHKey) < controllingHKey.getEncodedSize()) {
                    close();
                }
            }
        }

        HKeyAndDescendentsScan(PersistitHKey hKey) throws PersistitException
        {
            hKey.copyTo(exchange.getKey());
            hKey.copyTo(controllingHKey);
        }

        private boolean first = true;
    }

    private class HKeyWithoutDescendentsScan implements GroupScan
    {
        @Override
        public void advance() throws PersistitException, InvalidOperationException
        {
            if (first) {
                exchange.fetch();
                if (!exchange.getValue().isDefined()) {
                    close();
                }
                first = false;
            } else {
                close();
            }
        }

        HKeyWithoutDescendentsScan(PersistitHKey hKey) throws PersistitException
        {
            hKey.copyTo(exchange.getKey());
        }

        private boolean first = true;
    }

    private class HKeyRangeAndDescendentsScan implements GroupScan
    {
        @Override
        public void advance() throws PersistitException, InvalidOperationException
        {
            if (first) {
                if (!exchange.traverse(Key.GTEQ, hKeyRangeFilter, VALUE_BYTES)) {
                    close();
                } else {
                    exchange.getKey().copyTo(controllingHKey);
                }
                first = false;
            } else {
                if (!exchange.traverse(Key.GT, true)) {
                    close();
                } else {
                    if (exchange.getKey().firstUniqueByteIndex(controllingHKey) < controllingHKey.getEncodedSize()) {
                        // Current key is not a descendent of the controlling hkey
                        if (hKeyRangeFilter.selected(exchange.getKey())) {
                            // But it is still selected by hKeyRange
                            exchange.getKey().copyTo(controllingHKey);
                        } else {
                            // Not selected. Could be that we need to skip over some orphans.
                            if (!exchange.traverse(Key.GT, hKeyRangeFilter, VALUE_BYTES)) {
                                close();
                            } else {
                                exchange.getKey().copyTo(controllingHKey);
                            }
                        }
                    }
                }
            }
        }

        HKeyRangeAndDescendentsScan(IndexKeyRange hKeyRange, Bindings bindings) throws PersistitException
        {
            UserTable table = (hKeyRange.lo() == null ? hKeyRange.hi() : hKeyRange.lo()).table();
            RowDef rowDef = (RowDef) table.rowDef();
            hKeyRangeFilter = adapter.filterFactory.computeHKeyFilter(exchange.getKey(), rowDef, hKeyRange, bindings);
        }

        private final KeyFilter hKeyRangeFilter;
        private boolean first = true;
    }
}
