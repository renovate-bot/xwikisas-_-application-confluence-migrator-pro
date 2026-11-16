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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.LocalDocumentReference;
import org.xwiki.stability.Unstable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.api.Document;
import com.xpn.xwiki.api.Object;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xwiki.confluencepro.MigrationExtraDetails;

/**
 * Default implementation of the {@BatchCreator}.
 *
 * @version $Id$
 * @since 1.31.1
 */
@Singleton
@Unstable
@Component
public class DefaultBatchCreator extends AbstractBatchCreator
{
    private static final String FALSE = "false";

    private static final String TRUE = "true";

    private static final Pattern MIGRATION_BASENAME_PATTERN =
        Pattern.compile("(?:.*/)?([^/]+?)(?:\\.zip)?$", Pattern.CASE_INSENSITIVE);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String CONFLUENCE_MIGRATOR_PRO = "ConfluenceMigratorPro";

    private static final List<String> SPACE = List.of(CONFLUENCE_MIGRATOR_PRO, "Migrations");

    private static final String WOULD_CREATE = "Would create ";

    @Inject
    private MigrationExtraDetails migrationExtraDetails;
    @Inject
    @Named("compactwiki")
    private EntityReferenceSerializer<String> serializer;

    @Inject
    private Logger logger;

    @Override
    public Map<String, List<String>> createBatch(String batchName, List<String> sources, String inputProperties,
        String outputProperties, Map<String, String> extraParams) throws JsonProcessingException, XWikiException
    {
        Map<String, List<String>> logs = new HashMap<>();
        List<Document> migrationDocs = new ArrayList<>();
        List<String> messageList = new ArrayList<>();

        ObjectNode ownInputProperties = (ObjectNode) OBJECT_MAPPER.readTree(inputProperties);
        ObjectNode ownOutputProperties = (ObjectNode) OBJECT_MAPPER.readTree(outputProperties);

        processSources(batchName, sources, ownInputProperties, ownOutputProperties, messageList, migrationDocs);

        logs.put("messageList", messageList);
        logs.put("batchPage", createBatchPage(batchName, sources, migrationDocs));

        return logs;
    }

    /**
     * Processes the sources and creates the migration pages.
     */
    private void processSources(String batchName, List<String> sources, ObjectNode ownInputProperties,
        ObjectNode ownOutputProperties, List<String> messageList, List<Document> migrationDocs)
        throws XWikiException, JsonProcessingException
    {
        XWikiContext context = contextProvider.get();

        for (String source : sources) {
            Matcher m = MIGRATION_BASENAME_PATTERN.matcher(source);
            if (m.matches()) {
                String migrationBaseName = m.group(1);
                Document migrationDoc =
                    getMigrationDocument(buildMigrationBaseName(batchName, migrationBaseName), SPACE);
                Object migrationObject = migrationDoc.getObject("ConfluenceMigratorPro.Code.MigrationClass", true);
                migrationObject.set("extensions", migrationExtraDetails.identifyDependencyVersions());
                migrationObject.set("licenseType", migrationExtraDetails.identifyLicenseType());
                ownInputProperties.put("source", ensureFilePrefix(source));
                migrationObject.set("inputProperties", ownInputProperties.toString());
                migrationObject.set("outputProperties", ownOutputProperties.toString());
                migrationDoc.save();
                messageList.add("Creating " + migrationDoc.getDocumentReference());
                migrationDocs.add(migrationDoc);
            } else {
                logger.error("Could not create a migration basename for package [{}]", source);
            }
        }
    }

    /**
     * Create the actual batch page.
     */
    private List<String> createBatchPage(String batchName, List<String> sources, List<Document> migrationDocs)
        throws XWikiException
    {
        LocalDocumentReference batchReference =
            new LocalDocumentReference(List.of(CONFLUENCE_MIGRATOR_PRO, "ConfluenceBatches", "Batches"), batchName);

        XWikiContext context = contextProvider.get();
        XWikiDocument batchPage = context.getWiki().getDocument(batchReference, context);
        Document document = new Document(batchPage, context);
        Object baseObject =
            document.getObject("ConfluenceMigratorPro.ConfluenceBatches.Code.ConfluenceBatchClass", true);

        baseObject.set("sources", sources);
        baseObject.set("migrations", migrationDocs.stream().map(d -> serializer.serialize(d.getDocumentReference()))
            .collect(Collectors.toList()));
        document.save();
        return List.of(serializer.serialize(batchReference));
    }

    /**
     * Makes sure that the source is prepended with 'file://'.
     *
     * @param source package source
     * @return source prepended by 'file://'
     */
    private String ensureFilePrefix(String source)
    {
        return source.startsWith("file:") ? source : "file://" + source;
    }

    /**
     * Builds the base name of the migration page.
     *
     * @param batchName name of the batch
     * @param migrationName name of the package
     * @return base name of the migration
     */
    private String buildMigrationBaseName(String batchName, String migrationName)
    {
        String stringBuilder = "Batch_" + batchName + "__CONTENT__" + migrationName;
        return stringBuilder;
    }
}
