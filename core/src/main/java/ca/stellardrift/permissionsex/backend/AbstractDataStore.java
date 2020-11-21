/*
 * PermissionsEx
 * Copyright (C) zml and PermissionsEx contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.stellardrift.permissionsex.backend;

import ca.stellardrift.permissionsex.PermissionsEngine;
import ca.stellardrift.permissionsex.PermissionsEx;
import ca.stellardrift.permissionsex.context.ContextValue;
import ca.stellardrift.permissionsex.data.CacheListenerHolder;
import ca.stellardrift.permissionsex.exception.PermissionsException;
import ca.stellardrift.permissionsex.context.ContextInheritance;
import ca.stellardrift.permissionsex.subject.ImmutableSubjectData;
import ca.stellardrift.permissionsex.datastore.DataStore;
import ca.stellardrift.permissionsex.datastore.DataStoreFactory;
import ca.stellardrift.permissionsex.datastore.StoreProperties;
import ca.stellardrift.permissionsex.exception.PermissionsLoadingException;
import ca.stellardrift.permissionsex.rank.RankLadder;
import ca.stellardrift.permissionsex.util.Util;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Futures;
import net.kyori.adventure.text.Component;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.util.CheckedSupplier;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * Base implementation of a data store that provides common points for other data stores to hook into.
 *
 * @param <T> self type
 * @param <C> config type
 */
public abstract class AbstractDataStore<T extends AbstractDataStore<T, C>, C> implements DataStore {
    private @MonotonicNonNull PermissionsEx<?> manager;
    private final StoreProperties<C> properties;
    protected final CacheListenerHolder<Map.Entry<String, String>, ImmutableSubjectData> listeners = new CacheListenerHolder<>();
    protected final CacheListenerHolder<String, RankLadder> rankLadderListeners = new CacheListenerHolder<>();
    protected final CacheListenerHolder<Boolean, ContextInheritance> contextInheritanceListeners = new CacheListenerHolder<>();

    protected AbstractDataStore(final StoreProperties<C> props) {
        this.properties = props;
    }

    @Override
    public String getName() {
        return this.properties.identifier();
    }

    protected PermissionsEx<?> getManager() {
        return this.manager;
    }

    protected C config() {
        return this.properties.config();
    }

    @Override
    public final boolean initialize(PermissionsEngine core) throws PermissionsLoadingException {
        this.manager = (PermissionsEx<?>) core;
        return initializeInternal();
    }

    protected abstract boolean initializeInternal() throws PermissionsLoadingException;

    @Override
    public final CompletableFuture<ImmutableSubjectData> getData(final String type, final String identifier, final Consumer<ImmutableSubjectData> listener) {
        requireNonNull(type, "type");
        requireNonNull(identifier, "identifier");

        CompletableFuture<ImmutableSubjectData> ret = getDataInternal(type, identifier);
        ret.thenRun(() -> {
            if (listener != null) {
                listeners.addListener(Maps.immutableEntry(type, identifier), listener);
            }
        });
        return ret;
    }

    @Override
    public final CompletableFuture<ImmutableSubjectData> setData(String type, String identifier, ImmutableSubjectData data) {
        requireNonNull(type, "type");
        requireNonNull(identifier, "identifier");

        final Map.Entry<String, String> lookupKey = Maps.immutableEntry(type, identifier);
        return setDataInternal(type, identifier, data)
                .thenApply(newData -> {
                    if (newData != null) {
                        listeners.call(lookupKey, newData);
                    }
                    return newData;
                });
    }

    protected <V> CompletableFuture<V> runAsync(CheckedSupplier<V, ?> supplier) {
        return Util.asyncFailableFuture(supplier, getManager().getAsyncExecutor());
    }

    protected CompletableFuture<Void> runAsync(Runnable run) {
        return CompletableFuture.runAsync(run, getManager().getAsyncExecutor());
    }

    /**
     * Apply default data when creating a new file.
     *
     * This consists of
     * <ul>
     *     <li>Modifying default data to give all permissions to a user connecting locally</li>
     * </ul>
     */
    protected final void applyDefaultData() {
        getData(PermissionsEx.SUBJECTS_DEFAULTS, PermissionsEx.SUBJECTS_DEFAULTS, null)
                .thenApply(data -> data.setDefaultValue(ImmutableSet.of(new ContextValue<>("localip", "127.0.0.1")), 1))
                .thenCompose(data -> setData(PermissionsEx.SUBJECTS_DEFAULTS, PermissionsEx.SUBJECTS_DEFAULTS, data));
    }

    protected abstract CompletableFuture<ImmutableSubjectData> getDataInternal(String type, String identifier);

    protected abstract CompletableFuture<ImmutableSubjectData> setDataInternal(String type, String identifier, ImmutableSubjectData data);

    @Override
    public final Iterable<Map.Entry<String, ImmutableSubjectData>> getAll(final String type) {
        requireNonNull(type, "type");
        return Iterables.transform(getAllIdentifiers(type),
                input -> Maps.immutableEntry(input, Futures.getUnchecked(getData(type, input, null))));
    }

    @Override
    public final <T> CompletableFuture<T> performBulkOperation(final Function<DataStore, T> function) {
        return Util.asyncFailableFuture(() -> performBulkOperationSync(function), getManager().getAsyncExecutor());
    }

    @Override
    public final CompletableFuture<RankLadder> getRankLadder(String ladderName, Consumer<RankLadder> listener) {
        requireNonNull(ladderName, "ladderName");
        CompletableFuture<RankLadder> ladder = getRankLadderInternal(ladderName);
        if (listener != null) {
            rankLadderListeners.addListener(ladderName.toLowerCase(), listener);
        }
        return ladder;
    }

    @Override
    public final CompletableFuture<RankLadder> setRankLadder(final String identifier, RankLadder ladder) {
        return setRankLadderInternal(identifier, ladder)
                .thenApply(newData -> {
                    if (newData != null) {
                        rankLadderListeners.call(identifier, newData);
                    }
                    return newData;
                });
    }


    protected abstract CompletableFuture<RankLadder> getRankLadderInternal(String ladder);
    protected abstract CompletableFuture<RankLadder> setRankLadderInternal(String ladder, RankLadder newLadder);

    @Override
    public final CompletableFuture<ContextInheritance> getContextInheritance(Consumer<ContextInheritance> listener) {
        CompletableFuture<ContextInheritance> inheritance = getContextInheritanceInternal();
        if (listener != null) {
            contextInheritanceListeners.addListener(true, listener);
        }
        return inheritance;
    }


    @Override
    public final CompletableFuture<ContextInheritance> setContextInheritance(ContextInheritance contextInheritance) {
        return setContextInheritanceInternal(contextInheritance)
                .thenApply(newData -> {
                    if (newData != null) {
                        contextInheritanceListeners.call(true, newData);
                    }
                    return newData;
                });
    }



    protected abstract CompletableFuture<ContextInheritance> getContextInheritanceInternal();
    protected abstract CompletableFuture<ContextInheritance> setContextInheritanceInternal(ContextInheritance contextInheritance);

    /**
     * Internally perform a bulk operation. Safe to call blocking operations from this method -- we're running it asyncly.
     *
     * @param function The function to run
     * @param <T> The
     * @return result of operation, after a save
     * @throws Exception if thrown by operation
     */
    protected abstract <T> T performBulkOperationSync(Function<DataStore, T> function) throws Exception;

    @Override
    public CompletableFuture<Void> moveData(String oldType, String oldIdentifier, String newType, String newIdentifier) {
        return isRegistered(oldType, oldIdentifier).thenCombine(isRegistered(newType, newIdentifier), (oldRegistered, newRegistered) -> {
            if (oldRegistered && !newRegistered) {
                return getData(oldType, oldIdentifier, null)
                        .thenCompose(oldData -> setData(newType, newIdentifier, oldData))
                        .thenCompose(newData -> setData(oldType, oldIdentifier, null))
                        .thenApply(inp -> (Void) null);
            } else {
                return Util.<Void>failedFuture(new PermissionsException(Messages.DATASTORE_MOVE_ERROR.toComponent()));
            }

        }).thenCompose(future -> future);
    }

    @Override
    public String serialize(ConfigurationNode node) throws PermissionsLoadingException {
        requireNonNull(node, "node");
        try {
            node.set(this.properties.config());
        } catch (SerializationException e) {
            throw new PermissionsLoadingException(Messages.DATASTORE_ERROR_SERIALIZE.toComponent(node.key()), e);
        }
        return this.properties.factory().name();
    }

    public abstract static class Factory<T extends AbstractDataStore<T, C>, C> implements DataStoreFactory {
        private final String name;
        private final Class<C> configType;
        /**
         * Function taking the name of a data store instance and its configuration, and creating the appropriate new object.
         */
        private final Function<StoreProperties<C>, T> newInstanceSupplier;
        private final Component friendlyName;

        protected Factory(final String name, final Class<C> clazz, final Function<StoreProperties<C>, T> newInstanceSupplier) {
            requireNonNull(name, "name");
            requireNonNull(clazz, "clazz");
            this.name = name;
            this.friendlyName = Component.text(name);
            this.configType = clazz;
            this.newInstanceSupplier = newInstanceSupplier;
        }

        @Override
        public Component friendlyName() {
            return this.friendlyName;
        }

        @Override
        public final String name() {
            return this.name;
        }

        @Override
        public final DataStore create(String identifier, ConfigurationNode config) throws PermissionsLoadingException {
            try {
                final C dataStoreConfig = config.get(this.configType);
                return this.newInstanceSupplier.apply(StoreProperties.of(identifier, dataStoreConfig, this));
            } catch (SerializationException e) {
                throw new PermissionsLoadingException(Messages.DATASTORE_ERROR_DESERIALIZE.toComponent(identifier), e);
            }
        }
    }
}
