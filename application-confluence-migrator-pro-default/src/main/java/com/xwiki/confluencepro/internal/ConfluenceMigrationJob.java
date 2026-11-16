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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import com.xwiki.licensing.LicenseType;
import com.xwiki.licensing.Licensor;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.filter.descriptor.FilterStreamDescriptor;
import org.xwiki.filter.input.InputFilterStreamFactory;
import org.xwiki.filter.job.FilterStreamConverterJobRequest;
import org.xwiki.filter.output.OutputFilterStreamFactory;
import org.xwiki.filter.type.FilterStreamType;
import org.xwiki.job.AbstractJob;
import org.xwiki.job.AbstractJobStatus;
import org.xwiki.job.GroupedJob;
import org.xwiki.job.Job;
import org.xwiki.job.JobGroupPath;
import org.xwiki.job.event.status.CancelableJobStatus;
import org.xwiki.job.event.status.JobStatus;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.query.QueryManager;

import com.xwiki.confluencepro.ConfluenceMigrationJobRequest;
import com.xwiki.confluencepro.ConfluenceMigrationJobStatus;
import com.xwiki.confluencepro.ConfluenceMigrationManager;

/**
 * The job that will migrate the confluence package into XWiki.
 *
 * @version $Id$
 * @since 1.0
 */
@Component
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
@Named(ConfluenceMigrationJob.JOBTYPE)
public class ConfluenceMigrationJob
    extends AbstractJob<ConfluenceMigrationJobRequest, ConfluenceMigrationJobStatus> implements GroupedJob
{
    /**
     * The identifier for the job.
     */
    public static final String JOBTYPE = "confluence.migration";

    private static final String CONFLUENCE_XML_ROLEHINT = "confluence+xml";

    private static final String XWIKI_INSTANCE_ROLEHINT = "xwiki+instance";

    private static final String FILTER_CONVERTER_ROLEHINT = "filter.converter";

    private static final String FALSE = "false";

    private static final int TRIAL_PAGE_COUNT = 200;

    private static final String MAX_PAGE_COUNT = "maxPageCount";

    private static final String SOURCE = "source";

    private static final JobGroupPath GROUP = new JobGroupPath(Arrays.asList("confluencemigratorpro", "migration"));

    @Inject
    @Named(CONFLUENCE_XML_ROLEHINT)
    private InputFilterStreamFactory inputFilterStreamFactory;

    @Inject
    @Named(XWIKI_INSTANCE_ROLEHINT)
    private OutputFilterStreamFactory outputFilterStreamFactory;

    @Inject
    @Named(FILTER_CONVERTER_ROLEHINT)
    private Provider<Job> filterJobProvider;

    @Inject
    private ConfluenceMigrationManager migrationManager;

    @Inject
    private Provider<Licensor> licensorProvider;

    @Inject
    private QueryManager queryManager;

    private ConfluenceMigrationJobStatus jobStatus;

    /**
     * @return the job type.
     */
    @Override
    public String getType()
    {
        return JOBTYPE;
    }

    @Override
    protected ConfluenceMigrationJobStatus createNewStatus(ConfluenceMigrationJobRequest request)
    {
        Job currentJob = this.jobContext.getCurrentJob();
        JobStatus currentJobStatus = currentJob != null ? currentJob.getStatus() : null;
        jobStatus = new ConfluenceMigrationJobStatus(request, currentJobStatus, observationManager, loggerManager);
        return jobStatus;
    }

    /**
     * Run the job.
     */
    @Override
    protected void runInternal() throws InterruptedException
    {
        boolean rightOnly = isGeneralParameterEnabled("rightOnly");
        boolean attachmentsOnly = isGeneralParameterEnabled("attachmentsOnly");
        migrationManager.disablePrerequisites();
        Map<String, Object> inputProperties = getFilterInputProperties();

        Map<String, Object> outputProperties = getFilterOutputProperties();

        String outputStreamRoleHint;
        if (rightOnly) {
            outputStreamRoleHint = ConfluenceRightsOnlyInstanceOutputFilterStream.ROLEHINT;
        } else if (attachmentsOnly) {
            outputStreamRoleHint = ConfluenceAttachmentsOnlyInstanceOutputFilterStream.ROLEHINT;
        } else {
            outputStreamRoleHint = XWIKI_INSTANCE_ROLEHINT;
        }

        maybeReducePageCount(inputProperties);

        FilterStreamConverterJobRequest filterJobRequest = new FilterStreamConverterJobRequest(
            FilterStreamType.unserialize(CONFLUENCE_XML_ROLEHINT), inputProperties,
            FilterStreamType.unserialize(outputStreamRoleHint), outputProperties);
        boolean interactive = !isGeneralParameterEnabled("skipQuestions");
        filterJobRequest.setInteractive(interactive);
        request.setInteractive(interactive);
        progressManager.pushLevelProgress(1, this);
        logger.info("Starting Filter Job");
        progressManager.startStep(this);
        Job filterJob = this.filterJobProvider.get();
        filterJob.initialize(filterJobRequest);
        setCancelable(filterJob);
        filterJob.run();

        progressManager.popLevelProgress(this);

        migrationManager.updateAndSaveMigration(getStatus());
        migrationManager.enablePrerequisites();
    }

    private void setCancelable(Job filterJob)
    {
        JobStatus filterJobStatus = filterJob.getStatus();
        if (filterJobStatus instanceof AbstractJobStatus) {
            ((AbstractJobStatus<?>) filterJobStatus).setCancelable(true);
            this.jobStatus.setFilterJobStatus((CancelableJobStatus) filterJobStatus);
        }
    }

    private void maybeReducePageCount(Map<String, Object> inputProperties)
    {
        int maxPageCount = getMaxPageCount(request);

        if (maxPageCount > -1 && maxPageCount <= TRIAL_PAGE_COUNT) {
            return;
        }

        Licensor licensor = null;
        try {
            licensor = licensorProvider.get();
        } catch (Exception e) {
            logger.warn("Failed to get the licensor, allowing everything", e);
        }

        if (licensor == null) {
            return;
        }

        // If there is no license, the user can still trial the application by importing one space and 200 of its pages.
        String wiki = request.getStatusDocumentReference().getWikiReference().getName();

        DocumentReference mainRef = new DocumentReference(wiki, Arrays.asList(
            "ConfluenceMigratorPro", "Code"), "MigrationClass");

        if (!licensor.hasLicensure(mainRef) || licensor.getLicense(mainRef) == null
            || licensor.getLicense(mainRef).getType().equals(LicenseType.TRIAL)
        ) {
            logger.warn("Missing valid paid license, limiting the number of imported pages to [{}]",
                TRIAL_PAGE_COUNT);
            inputProperties.put(MAX_PAGE_COUNT, TRIAL_PAGE_COUNT);
        }
    }

    private static int getMaxPageCount(ConfluenceMigrationJobRequest request)
    {
        int maxPageCount = -1;
        Object maxPageCountObject = request.getInputProperties().get(MAX_PAGE_COUNT);
        if (maxPageCountObject instanceof String) {
            try {
                maxPageCount = Integer.parseInt((String) maxPageCountObject);
            } catch (NumberFormatException e) {
                // ignore, assume the worst (-1).
            }
        } else if (maxPageCountObject instanceof Integer) {
            maxPageCount = (Integer) maxPageCountObject;
        }
        return maxPageCount;
    }

    private boolean isGeneralParameterEnabled(String parameterName)
    {
        return "true".equals(request.getOutputProperties().getOrDefault(parameterName, FALSE));
    }

    private Map<String, Object> getFilterOutputProperties()
    {
        FilterStreamDescriptor outputDescriptor = outputFilterStreamFactory.getDescriptor();
        Map<String, Object> outputProperties = outputDescriptor
            .getProperties()
            .stream()
            .collect(HashMap::new, (m, v) -> m.put(v.getId(), v.getDefaultValue()), HashMap::putAll);
        for (Map.Entry<String, Object> entry : request.getOutputProperties().entrySet()) {
            if (entry.getValue() != null && !entry.getValue().toString().isEmpty()) {
                outputProperties.put(entry.getKey(), entry.getValue());
            }
        }
        return outputProperties;
    }

    private Map<String, Object> getFilterInputProperties()
    {
        FilterStreamDescriptor inputDescriptor = inputFilterStreamFactory.getDescriptor();

        // Not using Collectors.toMap() because of https://bugs.openjdk.org/browse/JDK-8148463
        Map<String, Object> inputProperties = inputDescriptor
            .getProperties()
            .stream()
            .collect(HashMap::new, (m, v) -> m.put(v.getId(), v.getDefaultValue()), HashMap::putAll);

        if (getRequest().getConfluencePackage() != null) {
            inputProperties.put(SOURCE, getRequest().getConfluencePackage());
        }
        for (Map.Entry<String, Object> entry : request.getInputProperties().entrySet()) {
            if (entry.getValue() != null && !entry.getValue().toString().isEmpty()) {
                inputProperties.put(entry.getKey(), entry.getValue());
            }
        }
        Object sourceObject = inputProperties.getOrDefault(SOURCE, "");
        if (sourceObject instanceof String) {
            String source = (String) sourceObject;
            if (source.startsWith("/")) {
                inputProperties.put(SOURCE, "file://" + source);
            }
        }
        return inputProperties;
    }

    @Override
    public JobGroupPath getGroupPath()
    {
        return GROUP;
    }
}
