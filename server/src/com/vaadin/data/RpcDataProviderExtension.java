/*
 * Copyright 2000-2014 Vaadin Ltd.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import com.google.gwt.thirdparty.guava.common.collect.BiMap;
import com.google.gwt.thirdparty.guava.common.collect.HashBiMap;
import com.vaadin.data.Container.Indexed;
import com.vaadin.data.Container.Indexed.ItemAddEvent;
import com.vaadin.data.Container.Indexed.ItemRemoveEvent;
import com.vaadin.data.Container.ItemSetChangeEvent;
import com.vaadin.data.Container.ItemSetChangeListener;
import com.vaadin.data.Container.ItemSetChangeNotifier;
import com.vaadin.data.Property.ValueChangeEvent;
import com.vaadin.data.Property.ValueChangeListener;
import com.vaadin.data.Property.ValueChangeNotifier;
import com.vaadin.data.util.converter.Converter;
import com.vaadin.server.AbstractExtension;
import com.vaadin.server.ClientConnector;
import com.vaadin.server.KeyMapper;
import com.vaadin.shared.data.DataProviderRpc;
import com.vaadin.shared.data.DataRequestRpc;
import com.vaadin.shared.ui.grid.GridState;
import com.vaadin.shared.ui.grid.Range;
import com.vaadin.ui.Grid;
import com.vaadin.ui.Grid.CellReference;
import com.vaadin.ui.Grid.CellStyleGenerator;
import com.vaadin.ui.Grid.Column;
import com.vaadin.ui.Grid.RowReference;
import com.vaadin.ui.Grid.RowStyleGenerator;
import com.vaadin.ui.renderers.Renderer;

import elemental.json.Json;
import elemental.json.JsonArray;
import elemental.json.JsonObject;
import elemental.json.JsonValue;

/**
 * Provides Vaadin server-side container data source to a
 * {@link com.vaadin.client.ui.grid.GridConnector}. This is currently
 * implemented as an Extension hardcoded to support a specific connector type.
 * This will be changed once framework support for something more flexible has
 * been implemented.
 * 
 * @since 7.4
 * @author Vaadin Ltd
 */
public class RpcDataProviderExtension extends AbstractExtension {

    /**
     * ItemId to Key to ItemId mapper.
     * <p>
     * This class is used when transmitting information about items in container
     * related to Grid. It introduces a consistent way of mapping ItemIds and
     * its container to a String that can be mapped back to ItemId.
     * <p>
     * <em>Technical note:</em> This class also keeps tabs on which indices are
     * being shown/selected, and is able to clean up after itself once the
     * itemId &lrarr; key mapping is not needed anymore. In other words, this
     * doesn't leak memory.
     */
    public class DataProviderKeyMapper implements Serializable {
        private final BiMap<Integer, Object> indexToItemId = HashBiMap.create();
        private final BiMap<Object, String> itemIdToKey = HashBiMap.create();
        private Set<Object> pinnedItemIds = new HashSet<Object>();
        private Range activeRange = Range.withLength(0, 0);
        private long rollingIndex = 0;

        private DataProviderKeyMapper() {
            // private implementation
        }

        void setActiveRange(Range newActiveRange) {
            final Range[] removed = activeRange.partitionWith(newActiveRange);
            final Range[] added = newActiveRange.partitionWith(activeRange);

            removeActiveRows(removed[0]);
            removeActiveRows(removed[2]);
            addActiveRows(added[0]);
            addActiveRows(added[2]);

            activeRange = newActiveRange;
        }

        private void removeActiveRows(final Range deprecated) {
            for (int i = deprecated.getStart(); i < deprecated.getEnd(); i++) {
                final Integer ii = Integer.valueOf(i);
                final Object itemId = indexToItemId.get(ii);

                if (!isPinned(itemId)) {
                    itemIdToKey.remove(itemId);
                    indexToItemId.remove(ii);
                }
            }
        }

        private void addActiveRows(Range added) {
            if (added.isEmpty()) {
                // Some container.getItemIds() implementations just might be
                // expensive even for an empty range, so bail out early
                return;
            }

            List<?> newItemIds = container.getItemIds(added.getStart(),
                    added.length());
            Integer index = added.getStart();
            for (Object itemId : newItemIds) {
                /*
                 * We might be in a situation we have an index <-> itemId entry
                 * already. This happens when something was selected, scrolled
                 * out of view and now we're scrolling it back into view. It's
                 * unnecessary to overwrite it in that case.
                 * 
                 * Fun thought: considering branch prediction, it _might_ even
                 * be a bit faster to simply always run the code inside this
                 * if-state. But it sounds too stupid (and most often too
                 * insignificant) to try out.
                 */
                if (!indexToItemId.containsKey(index)) {
                    /*
                     * We might be in a situation where we have an itemId <->
                     * key entry already, but no index for it. This happens when
                     * something that is out of view is selected
                     * programmatically. In that case, we only want to add an
                     * index for that entry, and not overwrite the key.
                     */
                    if (!itemIdToKey.containsKey(itemId)) {
                        itemIdToKey.put(itemId, nextKey());
                    }

                    indexToItemId.forcePut(index, itemId);
                }
                index++;
            }
        }

        private String nextKey() {
            return String.valueOf(rollingIndex++);
        }

        String getKey(Object itemId) {
            String key = itemIdToKey.get(itemId);
            if (key == null) {
                key = nextKey();
                itemIdToKey.put(itemId, key);
            }
            return key;
        }

        /**
         * Gets keys for a collection of item ids.
         * <p>
         * If the itemIds are currently cached, the existing keys will be used.
         * Otherwise new ones will be created.
         * 
         * @param itemIds
         *            the item ids for which to get keys
         * @return keys for the {@code itemIds}
         */
        public List<String> getKeys(Collection<Object> itemIds) {
            if (itemIds == null) {
                throw new IllegalArgumentException("itemIds can't be null");
            }

            ArrayList<String> keys = new ArrayList<String>(itemIds.size());
            for (Object itemId : itemIds) {
                keys.add(getKey(itemId));
            }
            return keys;
        }

        /**
         * Gets the registered item id based on its key.
         * <p>
         * A key is used to identify a particular row on both a server and a
         * client. This method can be used to get the item id for the row key
         * that the client has sent.
         * 
         * @param key
         *            the row key for which to retrieve an item id
         * @return the item id corresponding to {@code key}
         * @throws IllegalStateException
         *             if the key mapper does not have a record of {@code key} .
         */
        public Object getItemId(String key) throws IllegalStateException {
            Object itemId = itemIdToKey.inverse().get(key);
            if (itemId != null) {
                return itemId;
            } else {
                throw new IllegalStateException("No item id for key " + key
                        + " found.");
            }
        }

        /**
         * Gets corresponding item ids for each of the keys in a collection.
         * 
         * @param keys
         *            the keys for which to retrieve item ids
         * @return a collection of item ids for the {@code keys}
         * @throws IllegalStateException
         *             if one or more of keys don't have a corresponding item id
         *             in the cache
         */
        public Collection<Object> getItemIds(Collection<String> keys)
                throws IllegalStateException {
            if (keys == null) {
                throw new IllegalArgumentException("keys may not be null");
            }

            ArrayList<Object> itemIds = new ArrayList<Object>(keys.size());
            for (String key : keys) {
                itemIds.add(getItemId(key));
            }
            return itemIds;
        }

        /**
         * Pin an item id to be cached indefinitely.
         * <p>
         * Normally when an itemId is not an active row, it is discarded from
         * the cache. Pinning an item id will make sure that it is kept in the
         * cache.
         * <p>
         * In effect, while an item id is pinned, it always has the same key.
         * 
         * @param itemId
         *            the item id to pin
         * @throws IllegalStateException
         *             if {@code itemId} was already pinned
         * @see #unpin(Object)
         * @see #isPinned(Object)
         * @see #getItemIds(Collection)
         */
        public void pin(Object itemId) throws IllegalStateException {
            if (isPinned(itemId)) {
                throw new IllegalStateException("Item id " + itemId
                        + " was pinned already");
            }
            pinnedItemIds.add(itemId);
        }

        /**
         * Unpin an item id.
         * <p>
         * This cancels the effect of pinning an item id. If the item id is
         * currently inactive, it will be immediately removed from the cache.
         * 
         * @param itemId
         *            the item id to unpin
         * @throws IllegalStateException
         *             if {@code itemId} was not pinned
         * @see #pin(Object)
         * @see #isPinned(Object)
         * @see #getItemIds(Collection)
         */
        public void unpin(Object itemId) throws IllegalStateException {
            if (!isPinned(itemId)) {
                throw new IllegalStateException("Item id " + itemId
                        + " was not pinned");
            }

            pinnedItemIds.remove(itemId);
            final Integer index = indexToItemId.inverse().get(itemId);
            if (index == null || !activeRange.contains(index.intValue())) {
                itemIdToKey.remove(itemId);
                indexToItemId.remove(index);
            }
        }

        /**
         * Checks whether an item id is pinned or not.
         * 
         * @param itemId
         *            the item id to check for pin status
         * @return {@code true} iff the item id is currently pinned
         */
        public boolean isPinned(Object itemId) {
            return pinnedItemIds.contains(itemId);
        }

        Object itemIdAtIndex(int index) {
            return indexToItemId.get(Integer.valueOf(index));
        }
    }

    /**
     * A helper class that handles the client-side Escalator logic relating to
     * making sure that whatever is currently visible to the user, is properly
     * initialized and otherwise handled on the server side (as far as
     * required).
     * <p>
     * This bookeeping includes, but is not limited to:
     * <ul>
     * <li>listening to the currently visible {@link com.vaadin.data.Property
     * Properties'} value changes on the server side and sending those back to
     * the client; and
     * <li>attaching and detaching {@link com.vaadin.ui.Component Components}
     * from the Vaadin Component hierarchy.
     * </ul>
     */
    private class ActiveRowHandler implements Serializable {
        /**
         * A map from index to the value change listener used for all of column
         * properties
         */
        private final Map<Integer, GridValueChangeListener> valueChangeListeners = new HashMap<Integer, GridValueChangeListener>();

        /**
         * The currently active range. Practically, it's the range of row
         * indices being cached currently.
         */
        private Range activeRange = Range.withLength(0, 0);

        /**
         * A hook for making sure that appropriate data is "active". All other
         * rows should be "inactive".
         * <p>
         * "Active" can mean different things in different contexts. For
         * example, only the Properties in the active range need
         * ValueChangeListeners. Also, whenever a row with a Component becomes
         * active, it needs to be attached (and conversely, when inactive, it
         * needs to be detached).
         * 
         * @param firstActiveRow
         *            the first active row
         * @param activeRowCount
         *            the number of active rows
         */
        public void setActiveRows(int firstActiveRow, int activeRowCount) {

            final Range newActiveRange = Range.withLength(firstActiveRow,
                    activeRowCount);

            // TODO [[Components]] attach and detach components

            /*-
             *  Example
             * 
             *  New Range:       [3, 4, 5, 6, 7]
             *  Old Range: [1, 2, 3, 4, 5]
             *  Result:    [1, 2][3, 4, 5]      []
             */
            final Range[] depractionPartition = activeRange
                    .partitionWith(newActiveRange);
            removeValueChangeListeners(depractionPartition[0]);
            removeValueChangeListeners(depractionPartition[2]);

            /*-
             *  Example
             *  
             *  Old Range: [1, 2, 3, 4, 5]
             *  New Range:       [3, 4, 5, 6, 7]
             *  Result:    []    [3, 4, 5][6, 7]
             */
            final Range[] activationPartition = newActiveRange
                    .partitionWith(activeRange);
            addValueChangeListeners(activationPartition[0]);
            addValueChangeListeners(activationPartition[2]);

            activeRange = newActiveRange;

            assert valueChangeListeners.size() == newActiveRange.length() : "Value change listeners not set up correctly!";
        }

        private void addValueChangeListeners(Range range) {
            for (Integer i = range.getStart(); i < range.getEnd(); i++) {

                final Object itemId = container.getIdByIndex(i);
                final Item item = container.getItem(itemId);

                assert valueChangeListeners.get(i) == null : "Overwriting existing listener";

                GridValueChangeListener listener = new GridValueChangeListener(
                        itemId, item);
                valueChangeListeners.put(i, listener);
            }
        }

        private void removeValueChangeListeners(Range range) {
            for (Integer i = range.getStart(); i < range.getEnd(); i++) {
                final GridValueChangeListener listener = valueChangeListeners
                        .remove(i);

                assert listener != null : "Trying to remove nonexisting listener";

                listener.removeListener();
            }
        }

        /**
         * Manages removed columns in active rows.
         * <p>
         * This method does <em>not</em> send data again to the client.
         * 
         * @param removedColumns
         *            the columns that have been removed from the grid
         */
        public void columnsRemoved(Collection<Column> removedColumns) {
            if (removedColumns.isEmpty()) {
                return;
            }

            for (GridValueChangeListener listener : valueChangeListeners
                    .values()) {
                listener.removeColumns(removedColumns);
            }
        }

        /**
         * Manages added columns in active rows.
         * <p>
         * This method sends the data for the changed rows to client side.
         * 
         * @param addedColumns
         *            the columns that have been added to the grid
         */
        public void columnsAdded(Collection<Column> addedColumns) {
            if (addedColumns.isEmpty()) {
                return;
            }

            for (GridValueChangeListener listener : valueChangeListeners
                    .values()) {
                listener.addColumns(addedColumns);
            }
        }

        /**
         * Handles the insertion of rows.
         * <p>
         * This method's responsibilities are to:
         * <ul>
         * <li>shift the internal bookkeeping by <code>count</code> if the
         * insertion happens above currently active range
         * <li>ignore rows inserted below the currently active range
         * <li>shift (and deactivate) rows pushed out of view
         * <li>activate rows that are inserted in the current viewport
         * </ul>
         * 
         * @param firstIndex
         *            the index of the first inserted rows
         * @param count
         *            the number of rows inserted at <code>firstIndex</code>
         */
        public void insertRows(int firstIndex, int count) {
            if (firstIndex < activeRange.getStart()) {
                moveListeners(activeRange, count);
                activeRange = activeRange.offsetBy(count);
            } else if (firstIndex < activeRange.getEnd()) {
                int end = activeRange.getEnd();
                // Move rows from first added index by count
                Range movedRange = Range.between(firstIndex, end);
                moveListeners(movedRange, count);
                // Remove excess listeners from extra rows
                removeValueChangeListeners(Range.withLength(end, count));
                // Add listeners for new rows
                final Range freshRange = Range.withLength(firstIndex, count);
                addValueChangeListeners(freshRange);
            } else {
                // out of view, noop
            }
        }

        /**
         * Handles the removal of rows.
         * <p>
         * This method's responsibilities are to:
         * <ul>
         * <li>shift the internal bookkeeping by <code>count</code> if the
         * removal happens above currently active range
         * <li>ignore rows removed below the currently active range
         * </ul>
         * 
         * @param firstIndex
         *            the index of the first removed rows
         * @param count
         *            the number of rows removed at <code>firstIndex</code>
         */
        public void removeRows(int firstIndex, int count) {
            Range removed = Range.withLength(firstIndex, count);
            if (removed.intersects(activeRange)) {
                final Range[] deprecated = activeRange.partitionWith(removed);
                // Remove the listeners that are no longer existing
                removeValueChangeListeners(deprecated[1]);

                // Move remaining listeners to fill the listener map correctly
                moveListeners(deprecated[2], -deprecated[1].length());
                activeRange = Range.withLength(activeRange.getStart(),
                        activeRange.length() - deprecated[1].length());

            } else {
                if (removed.getEnd() < activeRange.getStart()) {
                    /* firstIndex < lastIndex < start */
                    moveListeners(activeRange, -count);
                    activeRange = activeRange.offsetBy(-count);
                }
                /* else: end <= firstIndex, no need to do anything */
            }
        }

        /**
         * Moves value change listeners in map with given index range by count
         */
        private void moveListeners(Range movedRange, int diff) {
            if (diff < 0) {
                for (Integer i = movedRange.getStart(); i < movedRange.getEnd(); ++i) {
                    moveListener(i, i + diff);
                }
            } else if (diff > 0) {
                for (Integer i = movedRange.getEnd() - 1; i >= movedRange
                        .getStart(); --i) {
                    moveListener(i, i + diff);
                }
            } else {
                // diff == 0 should not happen. If it does, should be no-op
                return;
            }
        }

        private void moveListener(Integer oldIndex, Integer newIndex) {
            assert valueChangeListeners.get(newIndex) == null : "Overwriting existing listener";

            GridValueChangeListener listener = valueChangeListeners
                    .remove(oldIndex);
            assert listener != null : "Moving nonexisting listener.";
            valueChangeListeners.put(newIndex, listener);
        }
    }

    /**
     * A class to listen to changes in property values in the Container added
     * with {@link Grid#setContainerDatasource(Container.Indexed)}, and notifies
     * the data source to update the client-side representation of the modified
     * item.
     * <p>
     * One instance of this class can (and should) be reused for all the
     * properties in an item, since this class will inform that the entire row
     * needs to be re-evaluated (in contrast to a property-based change
     * management)
     * <p>
     * Since there's no Container-wide possibility to listen to any kind of
     * value changes, an instance of this class needs to be attached to each and
     * every Item's Property in the container.
     * 
     * @see Grid#addValueChangeListener(Container, Object, Object)
     * @see Grid#valueChangeListeners
     */
    private class GridValueChangeListener implements ValueChangeListener {
        private final Object itemId;
        private final Item item;

        public GridValueChangeListener(Object itemId, Item item) {
            /*
             * Using an assert instead of an exception throw, just to optimize
             * prematurely
             */
            assert itemId != null : "null itemId not accepted";
            this.itemId = itemId;
            this.item = item;

            internalAddColumns(getGrid().getColumns());
        }

        @Override
        public void valueChange(ValueChangeEvent event) {
            updateRowData(itemId);
        }

        public void removeListener() {
            removeColumns(getGrid().getColumns());
        }

        public void addColumns(Collection<Column> addedColumns) {
            internalAddColumns(addedColumns);
            updateRowData(itemId);
        }

        private void internalAddColumns(Collection<Column> addedColumns) {
            for (final Column column : addedColumns) {
                final Property<?> property = item.getItemProperty(column
                        .getPropertyId());
                if (property instanceof ValueChangeNotifier) {
                    ((ValueChangeNotifier) property)
                            .addValueChangeListener(this);
                }
            }
        }

        public void removeColumns(Collection<Column> removedColumns) {
            for (final Column column : removedColumns) {
                final Property<?> property = item.getItemProperty(column
                        .getPropertyId());
                if (property instanceof ValueChangeNotifier) {
                    ((ValueChangeNotifier) property)
                            .removeValueChangeListener(this);
                }
            }
        }
    }

    private final Indexed container;

    private final ActiveRowHandler activeRowHandler = new ActiveRowHandler();

    private DataProviderRpc rpc;

    private final ItemSetChangeListener itemListener = new ItemSetChangeListener() {
        @Override
        public void containerItemSetChange(ItemSetChangeEvent event) {

            if (event instanceof ItemAddEvent) {
                ItemAddEvent addEvent = (ItemAddEvent) event;
                int firstIndex = addEvent.getFirstIndex();
                int count = addEvent.getAddedItemsCount();
                insertRowData(firstIndex, count);
            }

            else if (event instanceof ItemRemoveEvent) {
                ItemRemoveEvent removeEvent = (ItemRemoveEvent) event;
                int firstIndex = removeEvent.getFirstIndex();
                int count = removeEvent.getRemovedItemsCount();
                removeRowData(firstIndex, count);
            }

            else {

                /*
                 * Clear everything we have in view, and let the client
                 * re-request for whatever it needs.
                 * 
                 * Why this shortcut? Well, since anything could've happened, we
                 * don't know what has happened. There are a lot of use-cases we
                 * can cover at once with this carte blanche operation:
                 * 
                 * 1) Grid is scrolled somewhere in the middle and all the
                 * rows-inview are removed. We need a new pageful.
                 * 
                 * 2) Grid is scrolled somewhere in the middle and none of the
                 * visible rows are removed. We need no new rows.
                 * 
                 * 3) Grid is scrolled all the way to the bottom, and the last
                 * rows are being removed. Grid needs to scroll up and request
                 * for more rows at the top.
                 * 
                 * 4) Grid is scrolled pretty much to the bottom, and the last
                 * rows are being removed. Grid needs to be aware that some
                 * scrolling is needed, but not to compensate for all the
                 * removed rows. And it also needs to request for some more rows
                 * to the top.
                 * 
                 * 5) Some ranges of rows are removed from view. We need to
                 * collapse the gaps with existing rows and load the missing
                 * rows.
                 * 
                 * 6) The ultimate use case! Grid has 1.5 pages of rows and
                 * scrolled a bit down. One page of rows is removed. We need to
                 * make sure that new rows are loaded, but not all old slots are
                 * occupied, since the page can't be filled with new row data.
                 * It also needs to be scrolled to the top.
                 * 
                 * So, it's easier (and safer) to do the simple thing instead of
                 * taking all the corner cases into account.
                 */

                Map<Integer, GridValueChangeListener> listeners = activeRowHandler.valueChangeListeners;
                for (GridValueChangeListener listener : listeners.values()) {
                    listener.removeListener();
                }

                listeners.clear();
                activeRowHandler.activeRange = Range.withLength(0, 0);
                keyMapper.setActiveRange(Range.withLength(0, 0));
                keyMapper.indexToItemId.clear();

                /* Mark as dirty to push changes in beforeClientResponse */
                bareItemSetTriggeredSizeChange = true;
                markAsDirty();
            }
        }
    };

    private final DataProviderKeyMapper keyMapper = new DataProviderKeyMapper();

    private KeyMapper<Object> columnKeys;

    /** RpcDataProvider should send the current cache again. */
    private boolean refreshCache = false;

    private RowReference rowReference;
    private CellReference cellReference;

    /** Set of updated item ids */
    private Set<Object> updatedItemIds = new LinkedHashSet<Object>();

    /**
     * Queued RPC calls for adding and removing rows. Queue will be handled in
     * {@link beforeClientResponse}
     */
    private List<Runnable> rowChanges = new ArrayList<Runnable>();

    /** Size possibly changed with a bare ItemSetChangeEvent */
    private boolean bareItemSetTriggeredSizeChange = false;

    /**
     * Creates a new data provider using the given container.
     * 
     * @param container
     *            the container to make available
     */
    public RpcDataProviderExtension(Indexed container) {
        this.container = container;
        rpc = getRpcProxy(DataProviderRpc.class);

        registerRpc(new DataRequestRpc() {
            @Override
            public void requestRows(int firstRow, int numberOfRows,
                    int firstCachedRowIndex, int cacheSize) {

                pushRowData(firstRow, numberOfRows, firstCachedRowIndex,
                        cacheSize);
            }

            @Override
            public void setPinned(String key, boolean isPinned) {
                Object itemId = keyMapper.getItemId(key);
                if (isPinned) {
                    // Row might already be pinned if it was selected from the
                    // server
                    if (!keyMapper.isPinned(itemId)) {
                        keyMapper.pin(itemId);
                    }
                } else {
                    keyMapper.unpin(itemId);
                }
            }
        });

        if (container instanceof ItemSetChangeNotifier) {
            ((ItemSetChangeNotifier) container)
                    .addItemSetChangeListener(itemListener);
        }

    }

    /**
     * {@inheritDoc}
     * <p>
     * RpcDataProviderExtension makes all actual RPC calls from this function
     * based on changes in the container.
     */
    @Override
    public void beforeClientResponse(boolean initial) {
        if (initial || bareItemSetTriggeredSizeChange) {
            /*
             * Push initial set of rows, assuming Grid will initially be
             * rendered scrolled to the top and with a decent amount of rows
             * visible. If this guess is right, initial data can be shown
             * without a round-trip and if it's wrong, the data will simply be
             * discarded.
             */
            int size = container.size();
            rpc.resetDataAndSize(size);

            int numberOfRows = Math.min(40, size);
            pushRowData(0, numberOfRows, 0, 0);
        } else {
            // Only do row changes if not initial response.
            for (Runnable r : rowChanges) {
                r.run();
            }

            // Send current rows again if needed.
            if (refreshCache) {
                int firstRow = activeRowHandler.activeRange.getStart();
                int numberOfRows = activeRowHandler.activeRange.length();

                pushRowData(firstRow, numberOfRows, firstRow, numberOfRows);
            }
        }

        for (Object itemId : updatedItemIds) {
            internalUpdateRowData(itemId);
        }

        // Clear all changes.
        rowChanges.clear();
        refreshCache = false;
        updatedItemIds.clear();
        bareItemSetTriggeredSizeChange = false;

        super.beforeClientResponse(initial);
    }

    private void pushRowData(int firstRowToPush, int numberOfRows,
            int firstCachedRowIndex, int cacheSize) {
        Range active = Range.withLength(firstRowToPush, numberOfRows);
        if (cacheSize != 0) {
            Range cached = Range.withLength(firstCachedRowIndex, cacheSize);
            active = active.combineWith(cached);
        }

        keyMapper.setActiveRange(active);

        List<?> itemIds = container.getItemIds(firstRowToPush, numberOfRows);
        JsonArray rows = Json.createArray();
        for (int i = 0; i < itemIds.size(); ++i) {
            rows.set(i, getRowData(getGrid().getColumns(), itemIds.get(i)));
        }
        rpc.setRowData(firstRowToPush, rows);

        activeRowHandler.setActiveRows(active.getStart(), active.length());
    }

    private JsonValue getRowData(Collection<Column> columns, Object itemId) {
        Item item = container.getItem(itemId);

        JsonObject rowData = Json.createObject();

        Grid grid = getGrid();

        for (Column column : columns) {
            Object propertyId = column.getPropertyId();

            Object propertyValue = item.getItemProperty(propertyId).getValue();
            JsonValue encodedValue = encodeValue(propertyValue,
                    column.getRenderer(), column.getConverter(),
                    grid.getLocale());

            rowData.put(columnKeys.key(propertyId), encodedValue);
        }

        final JsonObject rowObject = Json.createObject();
        rowObject.put(GridState.JSONKEY_DATA, rowData);
        rowObject.put(GridState.JSONKEY_ROWKEY, keyMapper.getKey(itemId));

        rowReference.set(itemId);

        CellStyleGenerator cellStyleGenerator = grid.getCellStyleGenerator();
        if (cellStyleGenerator != null) {
            setGeneratedCellStyles(cellStyleGenerator, rowObject, columns);
        }
        RowStyleGenerator rowStyleGenerator = grid.getRowStyleGenerator();
        if (rowStyleGenerator != null) {
            setGeneratedRowStyles(rowStyleGenerator, rowObject);
        }

        return rowObject;
    }

    private void setGeneratedCellStyles(CellStyleGenerator generator,
            JsonObject rowObject, Collection<Column> columns) {
        JsonObject cellStyles = null;
        for (Column column : columns) {
            Object propertyId = column.getPropertyId();
            cellReference.set(propertyId);
            String style = generator.getStyle(cellReference);
            if (style != null) {
                if (cellStyles == null) {
                    cellStyles = Json.createObject();
                }

                String columnKey = columnKeys.key(propertyId);
                cellStyles.put(columnKey, style);
            }
        }
        if (cellStyles != null) {
            rowObject.put(GridState.JSONKEY_CELLSTYLES, cellStyles);
        }

    }

    private void setGeneratedRowStyles(RowStyleGenerator generator,
            JsonObject rowObject) {
        String rowStyle = generator.getStyle(rowReference);
        if (rowStyle != null) {
            rowObject.put(GridState.JSONKEY_ROWSTYLE, rowStyle);
        }
    }

    /**
     * Makes the data source available to the given {@link Grid} component.
     * 
     * @param component
     *            the remote data grid component to extend
     */
    public void extend(Grid component, KeyMapper<Object> columnKeys) {
        this.columnKeys = columnKeys;
        super.extend(component);
    }

    /**
     * Informs the client side that new rows have been inserted into the data
     * source.
     * 
     * @param index
     *            the index at which new rows have been inserted
     * @param count
     *            the number of rows inserted at <code>index</code>
     */
    private void insertRowData(final int index, final int count) {
        if (rowChanges.isEmpty()) {
            markAsDirty();
        }

        /*
         * Since all changes should be processed in a consistent order, we don't
         * send the RPC call immediately. beforeClientResponse will decide
         * whether to send these or not. Valid situation to not send these is
         * initial response or bare ItemSetChange event.
         */
        rowChanges.add(new Runnable() {
            @Override
            public void run() {
                rpc.insertRowData(index, count);
            }
        });

        activeRowHandler.insertRows(index, count);
    }

    /**
     * Informs the client side that rows have been removed from the data source.
     * 
     * @param index
     *            the index of the first row removed
     * @param count
     *            the number of rows removed
     * @param firstItemId
     *            the item id of the first removed item
     */
    private void removeRowData(final int index, final int count) {
        if (rowChanges.isEmpty()) {
            markAsDirty();
        }

        /* See comment in insertRowData */
        rowChanges.add(new Runnable() {
            @Override
            public void run() {
                rpc.removeRowData(index, count);
            }
        });

        activeRowHandler.removeRows(index, count);
    }

    /**
     * Informs the client side that data of a row has been modified in the data
     * source.
     * 
     * @param itemId
     *            the item Id the row that was updated
     */
    public void updateRowData(Object itemId) {
        if (updatedItemIds.isEmpty()) {
            // At least one new item will be updated. Mark as dirty to actually
            // update before response to client.
            markAsDirty();
        }

        updatedItemIds.add(itemId);
    }

    private void internalUpdateRowData(Object itemId) {
        int index = container.indexOfId(itemId);
        if (index >= 0) {
            JsonValue row = getRowData(getGrid().getColumns(), itemId);
            JsonArray rowArray = Json.createArray();
            rowArray.set(0, row);
            rpc.setRowData(index, rowArray);
        }
    }

    /**
     * Pushes a new version of all the rows in the active cache range.
     */
    public void refreshCache() {
        if (!refreshCache) {
            refreshCache = true;
            markAsDirty();
        }
    }

    @Override
    public void setParent(ClientConnector parent) {
        if (parent == null) {
            // We're being detached, release various listeners

            activeRowHandler
                    .removeValueChangeListeners(activeRowHandler.activeRange);

            if (container instanceof ItemSetChangeNotifier) {
                ((ItemSetChangeNotifier) container)
                        .removeItemSetChangeListener(itemListener);
            }

        } else if (parent instanceof Grid) {
            Grid grid = (Grid) parent;
            rowReference = new RowReference(grid);
            cellReference = new CellReference(rowReference);
        } else {
            throw new IllegalStateException(
                    "Grid is the only accepted parent type");
        }
        super.setParent(parent);
    }

    /**
     * Informs this data provider that given columns have been removed from
     * grid.
     * 
     * @param removedColumns
     *            a list of removed columns
     */
    public void columnsRemoved(List<Column> removedColumns) {
        activeRowHandler.columnsRemoved(removedColumns);
    }

    /**
     * Informs this data provider that given columns have been added to grid.
     * 
     * @param addedColumns
     *            a list of added columns
     */
    public void columnsAdded(List<Column> addedColumns) {
        activeRowHandler.columnsAdded(addedColumns);
    }

    public DataProviderKeyMapper getKeyMapper() {
        return keyMapper;
    }

    protected Grid getGrid() {
        return (Grid) getParent();
    }

    /**
     * Converts and encodes the given data model property value using the given
     * converter and renderer. This method is public only for testing purposes.
     * 
     * @param renderer
     *            the renderer to use
     * @param converter
     *            the converter to use
     * @param modelValue
     *            the value to convert and encode
     * @param locale
     *            the locale to use in conversion
     * @return an encoded value ready to be sent to the client
     */
    public static <T> JsonValue encodeValue(Object modelValue,
            Renderer<T> renderer, Converter<?, ?> converter, Locale locale) {
        Class<T> presentationType = renderer.getPresentationType();
        T presentationValue;

        if (converter == null) {
            try {
                presentationValue = presentationType.cast(modelValue);
            } catch (ClassCastException e) {
                if (presentationType == String.class) {
                    // If there is no converter, just fallback to using
                    // toString().
                    // modelValue can't be null as Class.cast(null) will always
                    // succeed
                    presentationValue = (T) modelValue.toString();
                } else {
                    throw new Converter.ConversionException(
                            "Unable to convert value of type "
                                    + modelValue.getClass().getName()
                                    + " to presentation type "
                                    + presentationType.getName()
                                    + ". No converter is set and the types are not compatible.");
                }
            }
        } else {
            assert presentationType.isAssignableFrom(converter
                    .getPresentationType());
            @SuppressWarnings("unchecked")
            Converter<T, Object> safeConverter = (Converter<T, Object>) converter;
            presentationValue = safeConverter.convertToPresentation(modelValue,
                    safeConverter.getPresentationType(), locale);
        }

        JsonValue encodedValue = renderer.encode(presentationValue);

        return encodedValue;
    }

    private static Logger getLogger() {
        return Logger.getLogger(RpcDataProviderExtension.class.getName());
    }

}
