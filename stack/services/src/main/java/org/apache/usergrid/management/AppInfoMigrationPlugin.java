/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one or more
 *  *  contributor license agreements.  The ASF licenses this file to You
 *  * under the Apache License, Version 2.0 (the "License"); you may not
 *  * use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.  For additional information regarding
 *  * copyright in this work, please see the NOTICE file in the top level
 *  * directory of this distribution.
 *
 */
package org.apache.usergrid.management;


import com.google.common.base.Optional;
import com.google.inject.Inject;
import org.apache.usergrid.corepersistence.util.CpEntityMapUtils;
import org.apache.usergrid.corepersistence.util.CpNamingUtils;
import org.apache.usergrid.persistence.*;
import org.apache.usergrid.persistence.collection.EntityCollectionManager;
import org.apache.usergrid.persistence.collection.EntityCollectionManagerFactory;
import org.apache.usergrid.persistence.core.migration.data.MigrationInfoSerialization;
import org.apache.usergrid.persistence.core.migration.data.MigrationPlugin;
import org.apache.usergrid.persistence.core.migration.data.PluginPhase;
import org.apache.usergrid.persistence.core.migration.data.ProgressObserver;
import org.apache.usergrid.persistence.core.scope.ApplicationScope;
import org.apache.usergrid.persistence.entities.Group;
import org.apache.usergrid.persistence.exceptions.ApplicationAlreadyExistsException;
import org.apache.usergrid.persistence.graph.Edge;
import org.apache.usergrid.persistence.graph.GraphManager;
import org.apache.usergrid.persistence.graph.GraphManagerFactory;
import org.apache.usergrid.persistence.graph.SearchByEdgeType;
import org.apache.usergrid.persistence.graph.impl.SimpleSearchByEdge;
import org.apache.usergrid.persistence.graph.impl.SimpleSearchByEdgeType;
import org.apache.usergrid.persistence.model.entity.Entity;
import org.apache.usergrid.persistence.model.entity.Id;
import org.apache.usergrid.persistence.model.entity.SimpleId;
import org.apache.usergrid.utils.UUIDUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import rx.Observable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.usergrid.corepersistence.util.CpNamingUtils.getApplicationScope;
import static org.apache.usergrid.persistence.Schema.PROPERTY_NAME;

/**
 * Migration of appinfos collection to application_info collection.
 *
 * Part of USERGRID-448 "Remove redundant appinfos collections in ManagementServiceImpl"
 * https://issues.apache.org/jira/browse/USERGRID-448
 */
public class AppInfoMigrationPlugin implements MigrationPlugin {

    /** Old and deprecated SYSTEM_APP */
    public static final UUID SYSTEM_APP_ID = UUID.fromString( "b6768a08-b5d5-11e3-a495-10ddb1de66c3" );

    private static final Logger logger = LoggerFactory.getLogger(AppInfoMigrationPlugin.class);

    public static String PLUGIN_NAME = "appinfo-migration";

    @Inject
    final private MigrationInfoSerialization migrationInfoSerialization;

    @Inject
    final private EntityManagerFactory emf;

    @Inject
    final private EntityCollectionManagerFactory entityCollectionManagerFactory;

    @Inject
    final private GraphManagerFactory graphManagerFactory;
    private final ManagementService managementService;

    @Inject
    public AppInfoMigrationPlugin(
        EntityManagerFactory emf,
        MigrationInfoSerialization migrationInfoSerialization,
        EntityCollectionManagerFactory entityCollectionManagerFactory,
        GraphManagerFactory graphManagerFactory,
        BeanFactory beanFactory) {

        this.emf = emf;
        this.migrationInfoSerialization = migrationInfoSerialization;
        this.entityCollectionManagerFactory = entityCollectionManagerFactory;
        this.graphManagerFactory = graphManagerFactory;
        this.managementService = beanFactory.getBean(ManagementService.class);
    }

    public AppInfoMigrationPlugin(
        EntityManagerFactory emf,
        MigrationInfoSerialization migrationInfoSerialization,
        EntityCollectionManagerFactory entityCollectionManagerFactory,
        GraphManagerFactory graphManagerFactory,
        ManagementService managementService) {

        this.emf = emf;
        this.migrationInfoSerialization = migrationInfoSerialization;
        this.entityCollectionManagerFactory = entityCollectionManagerFactory;
        this.graphManagerFactory = graphManagerFactory;
        this.managementService = managementService;
    }


    @Override
    public String getName() {
        return PLUGIN_NAME;
    }


    @Override
    public int getMaxVersion() {
        return 2; // standalone plugin, happens once
    }


    @Override
    public PluginPhase getPhase() {
        return PluginPhase.MIGRATE;
    }


    @Override
    public void run(ProgressObserver observer) {

        final int version = migrationInfoSerialization.getVersion(getName());

        if (version == getMaxVersion()) {
            logger.debug("Skipping Migration Plugin: " + getName());
            return;
        }

        observer.start();
        AtomicInteger count = new AtomicInteger();
        //get old app infos to migrate
        final Observable<Entity> oldAppInfos = getOldAppInfos();
        oldAppInfos
            .doOnNext(oldAppInfoEntity -> {
                try {
                    migrateAppInfo(oldAppInfoEntity, observer);
                    count.incrementAndGet();
                }catch (Exception e){
                    if(e.getCause() instanceof ApplicationAlreadyExistsException){
                        count.incrementAndGet();
                        logger.info("Application already migrated",oldAppInfoEntity.getId().getUuid());
                    }else {
                        logger.error("Failed to migrate app info" + oldAppInfoEntity.getId().getUuid(), e);
                        throw new RuntimeException(e);
                    }
                }

            })
            .doOnCompleted(() -> {
                if(count.get()>0) {
                    migrationInfoSerialization.setVersion(getName(), getMaxVersion());
                }else{
                    logger.error("Failed to migrate any app infos");
                }
                observer.complete();
            }).toBlocking().lastOrDefault(null);

    }


    private void migrateAppInfo( org.apache.usergrid.persistence.model.entity.Entity oldAppInfoEntity, ProgressObserver observer) {
        // Get appinfos from the Graph, we don't expect many so use iterator
        final EntityManager managementEm = emf.getManagementEntityManager();

        Map oldAppInfoMap = CpEntityMapUtils.toMap(oldAppInfoEntity);

        final String name = (String) oldAppInfoMap.get(PROPERTY_NAME);

        try {
            final String orgName = name.split("/")[0];
            final String appName = name.split("/")[1];
            UUID applicationId = getUuid(oldAppInfoMap,"applicationUuid") ;
            applicationId = applicationId == null ?  getUuid(oldAppInfoMap,"appUuid") : applicationId ;
            //get app info from graph to see if it has been migrated already
            org.apache.usergrid.persistence.Entity appInfo = getApplicationInfo(applicationId);
            if (appInfo == null) {

                observer.update(getMaxVersion(), "Created application_info for " + appName);
                // create org->app connections, but not for apps in dummy "usergrid" internal organization
                if(!orgName.equals("usergrid")) { //avoid management org

                    EntityRef orgRef = managementEm.getAlias(Group.ENTITY_TYPE, orgName);
                    // create and connect new APPLICATION_INFO oldAppInfo to Organization
                    managementService.createApplication(orgRef.getUuid(), name, applicationId, null);
                }
            } else {
                //already migrated don't do anything
                observer.update(getMaxVersion(), "Received existing application_info for " + appName + " don't do anything");
            }

        } catch (Exception e) {
            String msg = "Exception writing application_info for " + name;
            logger.error(msg, e);
            observer.failed(getMaxVersion(), msg);
            throw new RuntimeException(e);
        }

    }

    private UUID getUuid(Map oldAppInfoMap, String key) {
        UUID applicationId;
        Object uuidObject = oldAppInfoMap.get(key);
        if (uuidObject instanceof UUID) {
            applicationId = (UUID) uuidObject;
        } else {
            applicationId = uuidObject == null ? null : UUIDUtils.tryExtractUUID(uuidObject.toString());
        }
        return applicationId;
    }


    /**
     * TODO: Use Graph to get application_info for an specified Application.
     */
    private org.apache.usergrid.persistence.Entity getApplicationInfo( final UUID appId ) throws Exception {

        final ApplicationScope managementAppScope = getApplicationScope(CpNamingUtils.MANAGEMENT_APPLICATION_ID);
        final EntityCollectionManager managementCollectionManager = entityCollectionManagerFactory.createCollectionManager(managementAppScope);

        Observable<Edge> edgesObservable = getApplicationInfoEdges(appId);
        //get the graph for all app infos
        Observable<org.apache.usergrid.persistence.model.entity.Entity> entityObs =
            edgesObservable.flatMap(edge -> {
                final Id appInfoId = edge.getTargetNode();
                return managementCollectionManager
                    .load(appInfoId)
                    .filter( entity ->{
                        //check for app id
                        return  entity != null ? entity.getId().getUuid().equals(appId) : false;
                    });
            });

        // don't expect many applications, so we block
        org.apache.usergrid.persistence.model.entity.Entity applicationInfo =
            entityObs.toBlocking().lastOrDefault(null);

        if ( applicationInfo == null ) {
            return null;
        }

        Class clazz = Schema.getDefaultSchema().getEntityClass(applicationInfo.getId().getType());

        org.apache.usergrid.persistence.Entity entity = EntityFactory.newEntity(
            applicationInfo.getId().getUuid(), applicationInfo.getId().getType(), clazz);

        entity.setProperties( CpEntityMapUtils.toMap( applicationInfo ) );

        return entity;

    }


    /**
     * Use Graph to get old appinfos from the old and deprecated System App.
     */
    public Observable<org.apache.usergrid.persistence.model.entity.Entity> getOldAppInfos( ) {

        final ApplicationScope systemAppScope = getApplicationScope(SYSTEM_APP_ID);

        final EntityCollectionManager systemCollectionManager =
            entityCollectionManagerFactory.createCollectionManager(systemAppScope);

        final GraphManager gm = graphManagerFactory.createEdgeManager(systemAppScope);

        String edgeType = CpNamingUtils.getEdgeTypeFromCollectionName("appinfos");

        Id rootAppId = systemAppScope.getApplication();

        final SimpleSearchByEdgeType simpleSearchByEdgeType =  new SimpleSearchByEdgeType(
            rootAppId, edgeType, Long.MAX_VALUE, SearchByEdgeType.Order.DESCENDING, Optional.absent());

        Observable<org.apache.usergrid.persistence.model.entity.Entity> entityObs =
            gm.loadEdgesFromSource( simpleSearchByEdgeType )
                .flatMap(edge -> {
                    final Id appInfoId = edge.getTargetNode();

                    return systemCollectionManager.load(appInfoId)
                        .filter(entity -> (entity != null));
                });

        return entityObs;
    }

    public Observable<Edge> getApplicationInfoEdges(final UUID applicationId) {
        final ApplicationScope managementAppScope = getApplicationScope(CpNamingUtils.MANAGEMENT_APPLICATION_ID);
        final GraphManager gm = graphManagerFactory.createEdgeManager(managementAppScope);

        String edgeType = CpNamingUtils.getEdgeTypeFromCollectionName(CpNamingUtils.APPLICATION_INFOS);


        final SimpleSearchByEdge simpleSearchByEdgeType =  new SimpleSearchByEdge(
            CpNamingUtils.generateApplicationId(CpNamingUtils.MANAGEMENT_APPLICATION_ID), edgeType, CpNamingUtils.generateApplicationId( applicationId ), Long.MAX_VALUE, SearchByEdgeType.Order.DESCENDING,
            Optional.absent());

        return gm.loadEdgeVersions(simpleSearchByEdgeType  );
    }
}