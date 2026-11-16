/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package com.xwiki.confluencepro.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import com.xwiki.confluencepro.ConfluenceMigrationJobStatus;
import com.xwiki.pro.internal.resolvers.LinkMappingStore;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.confluence.filter.event.ConfluenceFilteringEvent;
import org.xwiki.contrib.confluence.filter.input.ConfluenceXMLPackage;
import org.xwiki.contrib.confluence.filter.input.LinkMapper;
import org.xwiki.contrib.confluence.filter.internal.input.ConfluenceLinkMappingReceiver;
import org.xwiki.filter.FilterException;
import org.xwiki.job.AbstractJobStatus;
import org.xwiki.job.Job;
import org.xwiki.job.JobContext;
import org.xwiki.job.Request;
import org.xwiki.job.event.status.JobStatus;
import org.slf4j.Logger;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.observation.AbstractEventListener;
import org.xwiki.observation.event.Event;
import org.xwiki.refactoring.job.question.EntitySelection;

/**
 * Listener that will ask different questions when the Confluence migration job runs.
 *
 * @version $Id$
 * @since 3.0
 */
@Component
@Named("com.xwiki.confluencepro.internal.ConfluenceFilteringListener")
@Singleton
public class ConfluenceFilteringListener extends AbstractEventListener
{
    static final Marker COLLISION_MARKER = MarkerFactory.getMarker("collidingReferences");

    @Inject
    private JobContext jobContext;

    @Inject
    private LinkMapper linkMapper;

    @Inject
    private Logger logger;

    @Inject
    private LinkMappingStore linkMappingStore;

    @Inject
    private EntityReferenceSerializer<String> serializer;

    /**
     * Default constructor.
     */
    public ConfluenceFilteringListener()
    {
        super(ConfluenceFilteringListener.class.getName(),
            Collections.singletonList(new ConfluenceFilteringEvent()));
    }

    @Override
    public void onEvent(Event event, Object source, Object data)
    {
        Job job = this.jobContext.getCurrentJob();
        ConfluenceMigrationJobStatus status = getConfluenceMigrationJobStatus(job);

        if (status == null) {
            logger.debug("Could not get the job status. Maybe the migration was not run from Confluence Migrator Pro?");
            return;
        }

        ConfluenceXMLPackage confluencePackage = (ConfluenceXMLPackage) data;

        ConfluenceFilteringEvent ev = (ConfluenceFilteringEvent) event;
        setSpaces(confluencePackage, status, ev, job);

        if (status.isCanceled()) {
            ev.cancel();
        } else {
            if (isInputPropertyEnabled(status, "storeConfluenceDetailsEnabled") && linkMappingStore.isSupported()) {
                // This is the happy path / normal situation.
                // The data about  the imported spaces should be in the wiki (except if someone has imported a partial
                // space and also disabled storeConfluenceDetailsEnabled on the previous import, which is not supported
                // nor likely).
                // We clean up the link mapping which may have been imported in a link-mapping only phase (with a
                // previous version of the migrator that was capable of doing so, but we removed this feature): we don't
                // want this data hanging around for no reason.
                linkMappingStore.removeSpaces(status.getSpaces());
            }
            lookForCollisions();
        }

        try {
            confluencePackage.readTasks();
        } catch (FilterException e) {
            logger.warn("Failed to read tasks, the tasks may lack some details", e);
        }
    }

    private void setSpaces(ConfluenceXMLPackage confluencePackage, ConfluenceMigrationJobStatus status,
        ConfluenceFilteringEvent ev, Job job)
    {
        Collection<String> spaces = new ArrayList<>(confluencePackage.getSpaceKeys(false));

        status.setSpaceTargets(ev.getSpaceTargets());

        for (Long spaceId : ev.getDisabledSpaces()) {
            try {
                spaces.remove(confluencePackage.getSpaceKey(spaceId));
            } catch (ConfigurationException e) {
                logger.error("Failed to disable import for space id [{}]. This is unexpected.", spaceId, e);
            }
        }

        status.setSpaces(spaces);

        if (shouldAskQuestions(status, job, spaces)) {
            askSpacesQuestion(ev, confluencePackage, status, spaces);
        }
    }

    private boolean shouldAskQuestions(ConfluenceMigrationJobStatus status, Job job, Collection<String> spaces)
    {
        if (spaces.size() < 2) {
            return false;
        }

        if (isOutputPropertyEnabled(status, "skipQuestions")) {
            return false;
        }

        Request request = job.getRequest();
        if (request == null) {
            return true;
        }
        return request.isInteractive();
    }

    private boolean isOutputPropertyEnabled(ConfluenceMigrationJobStatus jobStatusToAsk, String propertyName)
    {
        return isPropertyEnabled(jobStatusToAsk.getRequest().getOutputProperties(), propertyName);
    }

    private boolean isInputPropertyEnabled(ConfluenceMigrationJobStatus jobStatusToAsk, String propertyName)
    {
        return isPropertyEnabled(jobStatusToAsk.getRequest().getInputProperties(), propertyName);
    }

    private boolean isPropertyEnabled(Map<String, Object> properties, String propertyName)
    {
        Object v = properties.get(propertyName);
        if (v instanceof String) {
            return isTrue((String) v);
        }

        if (v instanceof Boolean) {
            return (Boolean) v;
        }

        if (v != null) {
            logger.error("Failed to determine if [{}]'s value is truthy, will assume false", propertyName);
        }

        return false;
    }

    static boolean isTrue(String v)
    {
        return "true".equals(v) || "1".equals(v);
    }

    private static void askSpacesQuestion(ConfluenceFilteringEvent event, ConfluenceXMLPackage confluencePackage,
        ConfluenceMigrationJobStatus jobStatusToAsk, Collection<String> spaces)
    {
        SpaceQuestion question =
            new ConfluenceQuestionManager().createAndAskQuestion(event, confluencePackage, jobStatusToAsk);

        for (EntitySelection entitySelection : question.getConfluenceSpaces().keySet()) {
            if (!entitySelection.isSelected()) {
                String spaceKey = entitySelection.getEntityReference().getName();
                Long spaceId = confluencePackage.getSpaceId(spaceKey);
                event.disableSpace(spaceId);
                spaces.remove(spaceKey);
            }
        }
    }

    private ConfluenceMigrationJobStatus getConfluenceMigrationJobStatus(Job job)
    {
        JobStatus jobStatus = job.getStatus();
        while (jobStatus != null) {
            if (jobStatus instanceof ConfluenceMigrationJobStatus) {
                return (ConfluenceMigrationJobStatus) jobStatus;
            }
            jobStatus = ((AbstractJobStatus<?>) jobStatus).getParentJobStatus();
        }
        return null;
    }

    private void lookForCollisions()
    {
        logger.info("Looking for collisions…");

        AtomicReference<String> currentSpace = new AtomicReference<>();

        Map<String, List<String>> reverseMapping = new HashMap<>();
        Set<String> collidingReferences = new HashSet<>();
        Map<String, String> spaceByRef = new HashMap<>();

        ConfluenceLinkMappingReceiver mapper = new MyConfluenceLinkMappingReceiver(currentSpace,
            reverseMapping, collidingReferences, spaceByRef);
        linkMapper.getLinkMapping(mapper);
        for (String collidingReference : collidingReferences) {
            String spaceKey = spaceByRef.get(collidingReference);
            List<String> pageTitles = reverseMapping.get(collidingReference);
            logger.error(COLLISION_MARKER, "Reference [{}] collides in space [{}] for pages [{}]",
                collidingReference, spaceKey, pageTitles);
        }
    }

    private final class MyConfluenceLinkMappingReceiver implements ConfluenceLinkMappingReceiver
    {
        private final AtomicReference<String> currentSpace;
        private final Map<String, List<String>> reverseMapping;
        private final Set<String> collidingReferences;
        private final Map<String, String> spaceByRef;

        private MyConfluenceLinkMappingReceiver(AtomicReference<String> currentSpace,
            Map<String, List<String>> reverseMapping, Set<String> collidingReferences,
            Map<String, String> spaceByRef)
        {
            this.currentSpace = currentSpace;
            this.reverseMapping = reverseMapping;
            this.collidingReferences = collidingReferences;
            this.spaceByRef = spaceByRef;
        }

        @Override
        public void addPage(String spaceKey, long pageId, EntityReference reference)
        {
            updateCurrentSpace(spaceKey);
        }

        @Override
        public void addPage(String spaceKey, String pageTitle, EntityReference reference)
        {
            updateCurrentSpace(spaceKey);
            String serialized = serializer.serialize(reference);

            // for collision checking
            List<String> list = reverseMapping.get(serialized);
            if (list == null) {
                list = new ArrayList<>(1);
                reverseMapping.put(serialized, list);
            } else {
                collidingReferences.add(serialized);
                spaceByRef.put(serialized, spaceKey);
            }
            list.add(pageTitle);
        }

        private void updateCurrentSpace(String spaceKey)
        {
            if (!spaceKey.equals(currentSpace.get())) {
                currentSpace.set(spaceKey);
            }
        }
    }
}
