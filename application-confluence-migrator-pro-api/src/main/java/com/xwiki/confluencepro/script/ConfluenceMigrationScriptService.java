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
package com.xwiki.confluencepro.script;

import java.io.InputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.context.concurrent.ContextStoreManager;
import org.xwiki.job.Job;
import org.xwiki.job.JobExecutor;
import org.xwiki.job.event.status.JobStatus;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.script.service.ScriptService;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;

import com.xwiki.confluencepro.ConfluenceMigrationJobRequest;
import com.xwiki.confluencepro.ConfluenceMigrationPrerequisites;

/**
 * Expose various FilterStream related APIs to scripts.
 *
 * @version $Id$
 * @since 1.0
 */
@Component
@Named(ConfluenceMigrationScriptService.ROLEHINT)
@Singleton
public class ConfluenceMigrationScriptService implements ScriptService
{
    /**
     * The rolehint of the class.
     */
    public static final String ROLEHINT = "confluenceMigration";

    private static final String TRUE = "true";
    private static final String FALSE = "false";

    /**
     * Default input filter stream migration parameter values.
     * @since 1.19.0
     */
    public static final Map<String, String> PREFILLED_INPUT_PARAMETERS = Map.of(
        "cleanup", "ASYNC",
        "storeConfluenceDetailsEnabled", TRUE,
        "unprefixedMacros", "info,toc,html,"
            + "panel,excerpt,expand,contributors,content-report-table,recently-updated,"
            + "excerpt-include,status,profile-picture,tasks-report-macro,date"
    );

    /**
     * Default output filter stream migration parameter values.
     * @since 1.19.0
     */
    public static final Map<String, String> PREFILLED_OUTPUT_PARAMETERS = Map.of(
        "versionPreserved", TRUE,
        "stoppedWhenSaveFail", FALSE
    );

    @Inject
    private ContextualAuthorizationManager authorization;

    @Inject
    private JobExecutor jobExecutor;

    @Inject
    private Logger logger;

    @Inject
    private ConfluenceMigrationPrerequisites prerequisites;

    @Inject
    private ContextStoreManager contextStoreManager;

    /**
     * @param documentReference the reference of the document that performs the migration.
     * @param confluencePackage the input stream for the package that will be used for the migration.
     * @param inputProperties the properties for the confluence input filter stream.
     * @param outputProperties the properties for the instance output filter stream.
     * @return the job.
     */
    public Job migrate(DocumentReference documentReference, InputStream confluencePackage,
        Map<String, Object> inputProperties, Map<String, Object> outputProperties)
    {
        if (!authorization.hasAccess(Right.ADMIN)) {
            return null;
        }
        Job lastJob = getLastJob(documentReference);
        if (lastJob != null && lastJob.getStatus().getState().equals(JobStatus.State.RUNNING)) {
            return lastJob;
        }
        ConfluenceMigrationJobRequest jobRequest =
            new ConfluenceMigrationJobRequest(confluencePackage, documentReference, inputProperties, outputProperties);
        try {
            Map<String, Serializable> migrationContext =
                this.contextStoreManager.save(Collections.singletonList("wiki"));
            jobRequest.setContext(migrationContext);
            return jobExecutor.execute("confluence.migration", jobRequest);
        } catch (Exception e) {
            logger.error("Failed to execute the migration job for [{}].", documentReference, e);
        }
        return null;
    }

    /**
     * @param documentReference the document reference associated with the desired job.
     * @return the last job that was executed or the currently executing job.
     */
    public Job getLastJob(DocumentReference documentReference)
    {
        return jobExecutor.getJob(ConfluenceMigrationJobRequest.getJobId(documentReference));
    }

    /**
     * Check if the maximum memory and the initial memory are at least half of the machine memory.
     *
     * @return the status of the memory check
     */
    public String checkMemory()
    {
        return prerequisites.checkMemory();
    }

    /**
     * Check the value of the xwiki.store.cache.capacity property from xwiki.cfg.
     *
     * @return the status of the cache check
     */
    public String checkCache()
    {
        return prerequisites.checkCache();
    }

    /**
     * Check the notifications.enabled and notifications.emails.enabled values from xwiki.properties.
     *
     * @return the status of the notifications check
     */
    public String checkWikiNotifications()
    {
        return prerequisites.checkWikiNotifications();
    }

    /**
     * Check the notification preferences in the current user's profile.
     *
     * @return the status of the current user's notification preferences check
     */
    public String checkCurrentUserNotification()
    {
        return prerequisites.checkCurrentUserNotification();
    }

    /**
     * Check if a given listener is enabled or not.
     *
     * @param listenerClassName the class name of the listener
     * @param isMandatory the flag to know the need (mandatory or optional)
     * @return the status of the listener state check
     */
    public String checkListener(String listenerClassName, boolean isMandatory)
    {
        return prerequisites.checkListener(listenerClassName, isMandatory);
    }

    /**
     * Get the machine memory.
     *
     * @return the formatted machine memory size
     */
    public String getMemory()
    {
        return prerequisites.readableSize(prerequisites.getMemory());
    }

    /**
     * Get the initial memory allocation pool.
     *
     * @return the formatted initial memory size
     */
    public String getXms()
    {
        return prerequisites.readableSize(prerequisites.getXms());
    }

    /**
     * Get the maximum memory allocation pool.
     *
     * @return formatted maximum memory size
     */
    public String getXmx()
    {
        return prerequisites.readableSize(prerequisites.getXmx());
    }

    /**
     * Get the xwiki.store.cache.capacity value from xwiki.cfg.
     *
     * @return the formatted cache size
     */
    public int getCache()
    {
        return prerequisites.getCache();
    }

    /**
     * Check if the current user's notifications are cleaned.
     *
     * @return the status of the clean check
     */
    public String checkCurrentUserNotificationCleanup()
    {
        return prerequisites.checkCurrentUserNotificationCleanup();
    }

    /**
     * @return a mutable copy of the prefilled parameters.
     *
     * @since 1.21.0
     */
    public Map<String, Map<String, String>> getMutablePrefilledConfiguration()
    {
        Map<String, Map<String, String>> prefilledValues = new HashMap<>(2);
        prefilledValues.put("input", new HashMap<>(PREFILLED_INPUT_PARAMETERS));
        prefilledValues.put("output", new HashMap<>(PREFILLED_OUTPUT_PARAMETERS));
        return prefilledValues;
    }
}
