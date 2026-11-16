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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiAttachmentContent;
import com.xpn.xwiki.store.DatabaseProduct;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.confluence.filter.internal.ConfluenceFilter;
import org.xwiki.logging.LogLevel;
import org.xwiki.logging.event.LogEvent;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.LocalDocumentReference;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xwiki.confluencepro.ConfluenceMigrationJobStatus;
import com.xwiki.confluencepro.ConfluenceMigrationManager;
import com.xwiki.confluencepro.MigrationExtraDetails;

import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;

import static com.xwiki.confluencepro.script.ConfluenceMigrationScriptService.PREFILLED_INPUT_PARAMETERS;
import static com.xwiki.confluencepro.script.ConfluenceMigrationScriptService.PREFILLED_OUTPUT_PARAMETERS;
import static org.xwiki.query.Query.HQL;

/**
 * The default implementation of {@link ConfluenceMigrationManager}.
 *
 * @version $Id$
 * @since 1.0
 */
@Component
@Singleton
public class DefaultConfluenceMigrationManager implements ConfluenceMigrationManager
{
    private static final String OCCURRENCES_KEY = "occurrences";

    private static final String PAGES_KEY = "pages";

    private static final List<String> CONFLUENCE_MIGRATOR_SPACE = Arrays.asList("ConfluenceMigratorPro", "Code");

    private static final LocalDocumentReference MIGRATION_OBJECT =
        new LocalDocumentReference(CONFLUENCE_MIGRATOR_SPACE, "MigrationClass");

    private static final Marker CONFLUENCE_REF_MARKER = MarkerFactory.getMarker("confluenceRef");

    private static final String EXECUTED = "executed";

    private static final String AN_EXCEPTION_OCCURRED = "An exception occurred";

    private static final String DATA_JSON = "data.json";

    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE_REF =
        new TypeReference<Map<String, String>>() { };
    private static final TypeReference<Map<String, Map<String, Integer>>> COUNT_MAP_TYPE_REF =
        new TypeReference<Map<String, Map<String, Integer>>>() { };
    private static final TypeReference<Map<String, Map<String, Set<?>>>> DOCS_MAP_TYPE_REF =
        new TypeReference<Map<String, Map<String, Set<?>>>>() { };

    private static final String PAGE_ID = "pageId";

    private static final Marker SEND_PAGE_MARKER = MarkerFactory.getMarker("ConfluenceSendingPage");

    private static final Marker SEND_TEMPLATE_MARKER = MarkerFactory.getMarker("ConfluenceSendingTemplate");

    private static final Marker UNHANDLED_PARAMETER_MARKER = MarkerFactory.getMarker("unhandledConfluenceParameter");
    private static final Marker UNHANDLED_PARAMETER_VALUE_MARKER =
        MarkerFactory.getMarker("unhandledConfluenceParameterValue");

    private static final String OTHER_ISSUES = "otherIssues";
    private static final String SKIPPED = "skipped";
    private static final String CONFLUENCE_REF_WARNINGS = "confluenceRefWarnings";
    private static final String UNHANDLED_PARAMETERS = "unhandledParameters";
    private static final String UNHANDLED_PARAMETER_VALUES = "unhandledParameterValues";

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private EntityReferenceSerializer<String> serializer;

    @Inject
    @Named("local")
    private EntityReferenceSerializer<String> localSerializer;

    @Inject
    private ConfluenceMigrationPrerequisitesManager prerequisitesManager;

    @Inject
    private Logger logger;

    @Inject
    private QueryManager queryManager;

    @Inject
    private EntityReferenceResolver<String> referenceResolver;

    @Inject
    private MigrationExtraDetails migrationExtraDetails;

    @Override
    public void updateAndSaveMigration(ConfluenceMigrationJobStatus jobStatus)
    {
        XWikiContext context = contextProvider.get();
        XWikiDocument document = null;
        BaseObject object = null;
        XWiki wiki = context.getWiki();
        DocumentReference statusDocumentReference = jobStatus.getRequest().getStatusDocumentReference();
        try {
            document = wiki.getDocument(statusDocumentReference, context).clone();
            object = document.getXObject(MIGRATION_OBJECT);
            object.set(EXECUTED, jobStatus.isCanceled() ? 3 : 1, context);
            object.setStringListValue("spaces", new ArrayList<>(jobStatus.getSpaces()));
            completeExtraDetails(object, context);
            String root = updateMigrationPropertiesAndGetRoot(object);
            Map<String, Map<String, Integer>> macroPages = analyseLogs(jobStatus, object, document, root);
            Map<String, Integer> macroCounts = new HashMap<>();
            for (Map<String, Integer> entries : macroPages.values()) {
                for (Map.Entry<String, Integer> entry : entries.entrySet()) {
                    String macroName = entry.getKey();
                    macroCounts.put(macroName, entry.getValue() + macroCounts.computeIfAbsent(macroName, k -> 0));
                }
            }
            object.setLargeStringValue("macros", new ObjectMapper().writeValueAsString(macroCounts));
            persistMacroMap(macroPages);

            if (StringUtils.isEmpty(document.getTitle())) {
                document.setTitle(statusDocumentReference.getName());
            }
            wiki.saveDocument(document, "Migration executed!", context);
            logger.info("Migration finished and saved");
        } catch (Exception e) {
            if (object != null) {
                object.set(EXECUTED, 4, context);
                logger.error(AN_EXCEPTION_OCCURRED, e);
                try {
                    wiki.saveDocument(document, "Migration failed", context);
                } catch (XWikiException err) {
                    logger.error("Could not update the migration document [{}] with an error status",
                        statusDocumentReference, err);
                }
            }
        }
    }

    private void completeExtraDetails(BaseObject object, XWikiContext context) throws JsonProcessingException
    {
        object.set("extensions", migrationExtraDetails.identifyDependencyVersions(), context);
        object.set("licenseType", migrationExtraDetails.identifyLicenseType(), context);
    }

    private String updateMigrationPropertiesAndGetRoot(BaseObject object)
    {
        try {
            removeDefaultProperties(object, "outputProperties", PREFILLED_OUTPUT_PARAMETERS);
            return removeDefaultProperties(object, "inputProperties", PREFILLED_INPUT_PARAMETERS).get("root");
        } catch (JsonProcessingException e) {
            logger.error("Could not save the input and output properties of the migration", e);
        }

        return null;
    }

    private Map<String, String> removeDefaultProperties(BaseObject object, String field, Map<String, String> defaults)
        throws JsonProcessingException
    {
        boolean update = false;
        String value = object.getLargeStringValue(field);
        Map<String, String> props = new ObjectMapper().readValue(value, STRING_MAP_TYPE_REF);
        for (Map.Entry<String, String> def : defaults.entrySet()) {
            String key = def.getKey();
            String v = props.get(key);
            if (v != null && v.equals(def.getValue())) {
                props.remove(key);
                update = true;
            }
        }
        if (update) {
            object.setLargeStringValue(field, new ObjectMapper().writeValueAsString(props));
        }
        return props;
    }

    private void replaceKey(Map<String, List<LogLine<SimpleLog>>> m, CurrentPage currentPage)
    {
        String oldKey = toString(currentPage.id);
        String newKey = currentPage.ref;
        if (m.containsKey(oldKey)) {
            List<LogLine<SimpleLog>> l = m.get(newKey);
            if (l == null) {
                m.put(newKey, m.remove(oldKey));
            } else {
                l.addAll(m.remove(oldKey));
            }
        }
    }

    private static final class LogLine<T>
    {
        // Keep the fields public: it's important for the JSON serialization.
        public Long pageId;
        public Long originalVersion;
        public String spaceKey;
        public String pageTitle;
        public T data;
    }

    private static final class SimpleLog
    {
        // Keep the fields public: it's important for the JSON serialization.
        public String level;
        public String marker;
        public String msg;
        public Object[] args;
    }

    private static final class CurrentPage
    {
        private Long id;
        private Long originalVersion;
        private String spaceKey;
        private String pageTitle;
        private String ref;

        private boolean isCurrentRevision()
        {
            return originalVersion == null || (originalVersion.equals(id));
        }

        private <T> LogLine<T> toLogLine(T data)
        {
            LogLine<T> logLine = new LogLine<>();
            logLine.data = data;
            logLine.pageId = id;
            logLine.originalVersion = originalVersion;
            logLine.spaceKey = spaceKey;
            logLine.pageTitle = pageTitle;
            return logLine;
        }

        private LogLine<SimpleLog> toLogLine(LogEvent e)
        {
            SimpleLog cl = new SimpleLog();
            cl.args = e.getArgumentArray();
            cl.marker = e.getMarker() == null ? "" : e.getMarker().getName();
            cl.msg = e.getMessage();
            cl.level = e.getLevel().name();
            return toLogLine(cl);
        }
    }

    private boolean ignoredIssue(LogEvent event)
    {
        if ("Failed to send event [{}] to listener [{}]".equals(event.getMessage())) {
            // Let's ignore useless but scary warnings that clutter reports
            // See https://github.com/xwikisas/application-confluence-migrator-pro/issues/88
            // See https://github.com/xwikisas/application-confluence-migrator-pro/issues/214
            Object[] args = event.getArgumentArray();
            return args.length == 2
                && args[0] instanceof DocumentUpdatedEvent
                && args[1] instanceof String
                && ((String) args[1]).startsWith("com.xpn.xwiki.internal.event.AttachmentEventGeneratorListener@");
        }
        return false;
    }

    private static final class DocCounts
    {
        private long templateCount;
        private long docCount;
        private long revisionCount;
    }

    Map<String, Map<String, Integer>> analyseLogs(ConfluenceMigrationJobStatus jobStatus,
        BaseObject object, XWikiDocument document, String root)
    {
        Map<String, Map<String, List<LogLine<SimpleLog>>>> logCategories = Map.of(
            OTHER_ISSUES, new TreeMap<>(),
            SKIPPED, new TreeMap<>(),
            CONFLUENCE_REF_WARNINGS, new TreeMap<>(),
            UNHANDLED_PARAMETERS, new TreeMap<>(),
            UNHANDLED_PARAMETER_VALUES, new TreeMap<>()
        );
        Map<String, Map<String, Integer>> macroPages = new HashMap<>();
        Map<String, Map<String, List<String>>> collisions = new HashMap<>();

        CurrentPage currentPage = new CurrentPage();
        DocCounts counts = new DocCounts();
        Collection<String> docs = new TreeSet<>();

        for (LogEvent event : jobStatus.getLogTail()) {
            if (event == null) {
                logger.warn("Found a null event. This is unexpected.");
                continue;
            }

            analyseLogEvent(event, currentPage, docs, logCategories, macroPages, counts, collisions);
        }

        for (Map.Entry<String, Map<String, List<LogLine<SimpleLog>>>> catEntry : logCategories.entrySet()) {
            addAttachment(catEntry.getKey() + ".json", catEntry.getValue(), document);
        }

        addAttachment("missingUsersGroups.json", getPermissionIssues(root, docs), document);
        addAttachment("collisions.json", collisions, document);
        addAttachment("spaceTargets.json", jobStatus.getSpaceTargets(), document);
        addAttachment("macroPages.json", macroPages, document);
        addAttachment("docs.json", docs, document);
        object.setLongValue("imported", counts.docCount);
        object.setLongValue("templates", counts.templateCount);
        object.setLongValue("revisions", counts.revisionCount);
        return macroPages;
    }

    private void analyseLogEvent(LogEvent event, CurrentPage currentPage, Collection<String> docs,
        Map<String, Map<String, List<LogLine<SimpleLog>>>> logCategories, Map<String, Map<String, Integer>> macroPages,
        DocCounts counts, Map<String, Map<String, List<String>>> collisions)
    {
        Marker marker = event.getMarker();
        if (isADocumentOutputFilterEvent(marker)) {
            updateCurrentPage(event, currentPage, docs, logCategories);
        } else if (ConfluenceFilter.LOG_MACROS_FOUND.equals(marker)) {
            addMacros(currentPage, event.getArgumentArray(), macroPages);
        } else if (SEND_PAGE_MARKER.equals(marker)) {
            updateCountsAndRef(event, currentPage, counts);
        } else if (SEND_TEMPLATE_MARKER.equals(marker)) {
            counts.templateCount++;
        } else if (UNHANDLED_PARAMETER_MARKER.equals(marker)) {
            addEventToCat(event, logCategories.get(UNHANDLED_PARAMETERS), currentPage);
        } else if (UNHANDLED_PARAMETER_VALUE_MARKER.equals(marker)) {
            addEventToCat(event, logCategories.get(UNHANDLED_PARAMETER_VALUES), currentPage);
        } else if (CONFLUENCE_REF_MARKER.equals(event.getMarker())) {
            addEventToCat(event, logCategories.get(CONFLUENCE_REF_WARNINGS), currentPage);
        } else if (LogLevel.WARN.equals(event.getLevel())) {
            addEventToCat(event, logCategories.get(OTHER_ISSUES), currentPage);
        } else if (LogLevel.ERROR.equals(event.getLevel())) {
            updateErrors(event, logCategories.get(SKIPPED), collisions, currentPage);
        }
    }

    private void updateCurrentPage(LogEvent event, CurrentPage currentPage, Collection<String> docs,
        Map<String, Map<String, List<LogLine<SimpleLog>>>> logCategories)
    {
        Object[] args = event.getArgumentArray();
        if (args.length > 0 && (args[0] instanceof DocumentReference)) {
            DocumentReference docRef = (DocumentReference) args[0];
            currentPage.ref = serializer.serialize(docRef);
            docs.add(localSerializer.serialize(docRef));
            if (currentPage.id != null) {
                for (Map<String, List<LogLine<SimpleLog>>> cat : logCategories.values()) {
                    replaceKey(cat, currentPage);
                }
            }
        }
    }

    private void updateErrors(LogEvent event, Map<String, List<LogLine<SimpleLog>>> skipped,
        Map<String, Map<String, List<String>>> collisions, CurrentPage currentPage)
    {
        Object[] args = event.getArgumentArray();
        if (isACollisionError(event, args)) {
            String collidingReference = (String) args[0];
            String spaceKey = (String) args[1];
            List<String> pages = (List<String>) args[2];
            Map<String, List<String>> spaceEntry = collisions.computeIfAbsent(spaceKey, k -> new HashMap<>());
            spaceEntry.put(collidingReference, pages);
        } else if (!ignoredIssue(event)) {
            addEventToCat(event, skipped, currentPage);
        }
    }

    private static boolean isACollisionError(LogEvent event, Object[] args)
    {
        if (Objects.equals(event.getMarker(), ConfluenceFilteringListener.COLLISION_MARKER)) {
            return args.length == 3
                && args[0] instanceof String
                && args[1] instanceof String
                && args[2] instanceof List;
        }
        return false;
    }

    private static void addMacros(CurrentPage currentPage, Object[] args, Map<String, Map<String, Integer>> macroPages)
    {
        if (currentPage.ref != null && currentPage.isCurrentRevision() && args[0] instanceof Map) {
            Map<String, Integer> macrosIds = (Map<String, Integer>) args[0];
            macroPages.put(currentPage.ref, mergeMacroIds(macrosIds, macroPages.get(currentPage.ref)));
        }
    }

    private static Map<String, Integer> mergeMacroIds(Map<String, Integer> newMacrosIds,
        Map<String, Integer> oldMacrosIds)
    {
        if (oldMacrosIds == null) {
            return newMacrosIds;
        }

        // We merge the counts of the different document translations because that's what seems to be the most intuitive
        // We expect translations to have about the same macro counts, but we don't want to miss some macro usage if
        // it's not the case
        // summing would provide surprisingly high numbers.

        Map<String, Integer> macroIds = new LinkedHashMap<>(newMacrosIds);
        for (Map.Entry<String, Integer> entry : oldMacrosIds.entrySet()) {
            String id = entry.getKey();
            macroIds.put(entry.getKey(), Math.max(entry.getValue(), macroIds.getOrDefault(id, 0)));
        }
        return macroIds;
    }

    private static boolean isADocumentOutputFilterEvent(Marker marker)
    {
        String markerName = (marker == null || marker.getName() == null) ? "" : marker.getName();
        return markerName.equals("filter.instance.log.document.updated")
            || markerName.equals("filter.instance.log.document.created");
    }

    private static <T> T getPageIdentifierField(Map<?, ?> pageIdentifier, String field, Class<T> clazz)
    {
        Object f = pageIdentifier.get(field);
        if (clazz.isInstance(f)) {
            return clazz.cast(f);
        }

        return null;
    }

    private static void updateCountsAndRef(LogEvent event, CurrentPage currentPage, DocCounts counts)
    {
        currentPage.ref = null;
        counts.revisionCount++;
        if (tryUpdateCurrentPage(currentPage, event.getArgumentArray())) {
            counts.docCount++;
        }
    }

    private static boolean tryUpdateCurrentPage(CurrentPage currentPage, Object[] args)
    {
        if (args.length > 0 && args[0] instanceof Map) {
            Map<?, ?> pageIdentifier = (Map<?, ?>) args[0];
            currentPage.id = getPageIdentifierField(pageIdentifier, PAGE_ID, Long.class);
            currentPage.originalVersion = getPageIdentifierField(pageIdentifier, "originalVersion", Long.class);
            currentPage.spaceKey = getPageIdentifierField(pageIdentifier, "spaceKey", String.class);
            currentPage.pageTitle = getPageIdentifierField(pageIdentifier, "pageTitle", String.class);
            return currentPage.originalVersion == null || currentPage.originalVersion.equals(currentPage.id);
        }
        return false;
    }

    private static void addEventToCat(LogEvent e, Map<String, List<LogLine<SimpleLog>>> cat, CurrentPage currentPage)
    {
        if (cat != null) {
            String pageIdOrFullName = getPageIdOrFullName(e, currentPage);
            if (pageIdOrFullName != null) {
                List<LogLine<SimpleLog>> logLines = cat.computeIfAbsent(pageIdOrFullName, k -> new ArrayList<>());
                logLines.add(currentPage.toLogLine(e));
            }
        }
    }

    private Map<String, List<String>> getPermissionIssues(String root, Collection<String> docs)
    {
        Map<String, List<String>> permissionIssues = new HashMap<>(2);
        if (checkNotOracle("Detecting missing users and groups from imported rights")) {
            String wiki = getWiki(root);
            putMissingSubjects(wiki, docs, permissionIssues, "groups");
            putMissingSubjects(wiki, docs, permissionIssues, "users");
        }
        return permissionIssues;
    }

    private boolean checkNotOracle(String feature)
    {
        DatabaseProduct db = contextProvider.get().getWiki().getHibernateStore().getDatabaseProductName();
        if (DatabaseProduct.ORACLE.equals(db)) {
            logger.warn("{} isn't possible on Oracle database at this time", feature);
            return false;
        }

        return true;
    }

    private String getWiki(String root)
    {
        if (StringUtils.isNotEmpty(root)) {
            if (root.startsWith("wiki:")) {
                return root.substring(5);
            }

            String r = root;
            if (r.startsWith("space:")) {
                r = r.substring(6);
            } else if (r.startsWith("document:")) {
                r = r.substring(9);
            }

            if (!r.isEmpty()) {
                EntityReference rootRef = referenceResolver.resolve(r, EntityType.SPACE).getRoot();
                if (rootRef.getType() == EntityType.WIKI) {
                    return rootRef.getName();
                }
            }
        }

        return contextProvider.get().getWikiId();
    }

    private void putMissingSubjects(String wiki, Collection<String> docs, Map<String, List<String>> res, String field)
    {
        try {
            res.put(field, new ArrayList<>(getMissingSubjects(field, wiki, docs)));
        } catch (QueryException e) {
            logger.error("Could not evaluate missing [{}] in permissions", field, e);
        }
    }

    private Set<String> getMissingSubjects(String field, String wiki, Collection<String> docs) throws QueryException
    {
        // Takes the list of document references pointing to users or groups and only keep those not existing in the
        // wiki.
        Set<String> subjects = getSubjects(field, wiki, docs);
        if (!subjects.isEmpty()) {
            // We assume the users and groups are in the current wiki.
            List<String> foundSubjects = queryManager.createQuery(
                    String.format(
                        "select o.name from BaseObject o where o.name in (:refs) and o.className = 'XWiki.XWiki%s'",
                        StringUtils.capitalize(field)),
                    HQL)
                .bindValue("refs", subjects)
                .execute();
            subjects.removeIf(foundSubjects::contains);
        }
        return subjects;
    }

    private Set<String> getSubjects(String field, String wiki, Collection<String> docs) throws QueryException
    {
        // Computes the list of right object 'users' and 'groups' containing a comma or for which there aren't any
        // corresponding users or groups.
        Set<String> subjects = new TreeSet<>();
        addSubjects(subjects, wiki, docs, field, "XWiki.XWikiRights");
        addSubjects(subjects, wiki, docs, field, "XWiki.XWikiGlobalRights");
        return subjects;
    }

    private void addSubjects(Set<String> subjects, String wiki, Collection<String> docs, String field,
        String rightObjectName) throws QueryException
    {
        // Select the users (or groups) in right objects without a corresponding XWikiUsers (or XWikiGroups) object.
        // This will also select users and groups fields that contains commas (which should not really happen with
        // current versions of confluence-xml at the time of writing, it does not do such clever things as grouping
        // right objects for different users or groups yet)
        // We handle those fields with comma later with a split and then a second query.
        // I wanted to write this in XWQL but https://jira.xwiki.org/browse/XWIKI-22621 prevents this here.

        // If documents were imported in the current wiki where we assume the users and groups to be, we can optimize
        // and already filter out known users and groups. Otherwise, we'd need a join with a separate database, which
        // is not possible.
        String optimizedFilter = contextProvider.get().getWikiId().equals(wiki)
            ? "p.value not in (select o.name from BaseObject o where o.className = 'XWiki.XWiki%3$s') and "
            : "";

        String queryString = String.format(
            "select distinct p.value from BaseObject obj, LargeStringProperty p where "
                + "obj.name in (:docs) and "
                + "p.value <> '' and "
                + "%4$s"
                + "obj.className = '%1$s' and "
                + "p.id.id = obj.id and "
                + "p.id.name = '%2$s'", rightObjectName, field, StringUtils.capitalize(field), optimizedFilter);

        Collection<String> entries = queryManager.createQuery(queryString, HQL)
            .setWiki(wiki)
            .bindValue("docs", docs)
            .execute();

        for (String subjectsWithCommas : entries) {
            subjects.addAll(List.of(subjectsWithCommas.split("\\s*,\\s*")));
        }
    }

    private void addAttachment(String name, Object obj, XWikiDocument document)
    {
        XWikiAttachment a = new XWikiAttachment(document, name);
        XWikiAttachmentContent content = new XWikiAttachmentContent(a);
        try {
            new ObjectMapper().writeValue(content.getContentOutputStream(), obj);
        } catch (IOException e) {
            logger.error("Could not save [{}]", name, e);
        }
        a.setAttachment_content(content);
        document.setAttachment(a);
    }

    private static String toString(Long id)
    {
        if (id == null) {
            return null;
        }
        return id.toString();
    }

    private static String getPageIdOrFullName(LogEvent logEvent, CurrentPage currentPage)
    {
        if (currentPage.ref == null) {
            for (Object arg : logEvent.getArgumentArray()) {
                if (arg instanceof Map) {
                    return ((Map<?, ?>) arg).get(PAGE_ID).toString();
                }
            }

            return toString(currentPage.id);
        }

        return currentPage.ref;
    }

    private void persistMacroMap(Map<String, Map<String, Integer>> macroPages)
    {
        Map<String, Map<String, Object>> macroMap = computeMacroMap(macroPages);
        XWikiContext context = contextProvider.get();
        DocumentReference migratedMacrosCountJSONDocRef = new DocumentReference(
            context.getWikiId(), CONFLUENCE_MIGRATOR_SPACE, "MigratedMacrosCountJSON");
        DocumentReference migratedMacrosDocsJSONDocRef = new DocumentReference(
            context.getWikiId(), CONFLUENCE_MIGRATOR_SPACE, "MigratedMacrosDocsJSON");
        logger.info("Saving the macro usage statistics in [{}] and [{}]",
            migratedMacrosCountJSONDocRef, migratedMacrosDocsJSONDocRef);
        try {
            XWikiDocument macroCountDoc = context.getWiki().getDocument(migratedMacrosCountJSONDocRef, context).clone();
            Map<String, Map<String, Integer>> countMap = contentToMap(macroCountDoc, COUNT_MAP_TYPE_REF);
            XWikiDocument macroDocsDoc = context.getWiki().getDocument(migratedMacrosDocsJSONDocRef, context).clone();
            Map<String, Map<String, Set<?>>> docsMap = contentToMap(macroDocsDoc, DOCS_MAP_TYPE_REF);

            prepareMacroMap(macroMap, countMap, docsMap);
            Map<String, Map<String, Integer>> sortedCounts = countMap.entrySet()
                .stream()
                .sorted((o1, o2) -> Integer.compare(o2.getValue().getOrDefault(OCCURRENCES_KEY, 0),
                    o1.getValue().getOrDefault(OCCURRENCES_KEY, 0)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (o1, o2) -> o2, LinkedHashMap::new));
            saveMacroData(macroCountDoc, sortedCounts);
            saveMacroData(macroDocsDoc, docsMap);
        } catch (XWikiException | IOException e) {
            logger.error("Failed to save the macro usage statistics", e);
        }
    }

    private <T> void saveMacroData(XWikiDocument d, T data) throws XWikiException, IOException
    {
        XWikiAttachment attachment = new XWikiAttachment(d, DATA_JSON);
        XWikiAttachmentContent content = new XWikiAttachmentContent(attachment);
        new ObjectMapper().writeValue(content.getContentOutputStream(), data);
        attachment.setAttachment_content(content);
        d.setAttachment(attachment);
        d.setHidden(true);
        XWikiContext context = contextProvider.get();
        context.getWiki().saveDocument(d, context);
    }

    private void prepareMacroMap(Map<String, Map<String, Object>> macroMap,
        Map<String, Map<String, Integer>> occurenceMap, Map<String, Map<String, Set<?>>> pagesMap)
    {
        for (Map.Entry<String, Map<String, Object>> macroEntry : macroMap.entrySet()) {
            String macroId = macroEntry.getKey();
            Map<String, Object> macroSpacesData = macroEntry.getValue();
            for (Map.Entry<String, Object> macroData : macroSpacesData.entrySet()) {
                String spaceKey = macroData.getKey();
                Map<String, Integer> macroOccurenceMap =
                    occurenceMap.computeIfAbsent(macroId, k -> new HashMap<>());

                if (macroData.getValue() instanceof Integer) {
                    int occurrences = macroOccurenceMap.getOrDefault(OCCURRENCES_KEY, 0);
                    int oldSpaceOccurrences = macroOccurenceMap.getOrDefault(spaceKey, 0);
                    int newSpaceOccurrences = (Integer) macroData.getValue();

                    macroOccurenceMap.put(OCCURRENCES_KEY, occurrences - oldSpaceOccurrences + newSpaceOccurrences);
                    macroOccurenceMap.put(spaceKey, newSpaceOccurrences);
                } else if (macroData.getValue() instanceof Set) {
                    Set<?> pages = (Set<?>) macroData.getValue();

                    int pagesCount = macroOccurenceMap.getOrDefault(PAGES_KEY, 0);
                    int oldPagesCount = macroOccurenceMap.getOrDefault(spaceKey, 0);
                    int newPagesCount = pages.size();
                    macroOccurenceMap.put(PAGES_KEY, pagesCount - oldPagesCount + newPagesCount);
                    macroOccurenceMap.put(spaceKey, newPagesCount);

                    pagesMap.computeIfAbsent(macroId, k -> new HashMap<>()).put(spaceKey, pages);
                }
            }
        }
    }

    private <T> Map<String, T> contentToMap(XWikiDocument doc, TypeReference<Map<String, T>> typeRef)
    {
        try {
            String dataStr = doc.getContent();
            if (!dataStr.isEmpty()) {
                // Old versions of the Confluence migrator saved the data in the document content, which causes
                // performance issues. We migrate the content to an attachment.
                doc.setContent("");
                return new ObjectMapper().readValue(dataStr, typeRef);
            }

            XWikiAttachment dataAttachment = doc.getAttachment(DATA_JSON);
            if (dataAttachment != null) {
                InputStream data = dataAttachment.getAttachmentContent(contextProvider.get()).getContentInputStream();
                return new ObjectMapper().readValue(data, typeRef);
            }
        } catch (XWikiException | IOException e) {
            logger.warn("Failed to read existing macro usage statistics from [{}]", doc, e);
        }

        return new HashMap<>();
    }

    private Map<String, Map<String, Object>> computeMacroMap(Map<String, Map<String, Integer>> macroPages)
    {
        Map<String, Map<String, Object>> macroMap = new HashMap<>();

        for (Map.Entry<String, Map<String, Integer>> macroPage : macroPages.entrySet()) {
            Map<String, Integer> pageMacroCount = macroPage.getValue();
            String page = macroPage.getKey();
            String space = page.substring(0, page.indexOf('.') > -1 ? page.indexOf('.') : page.length() - 1);
            for (Map.Entry<String, Integer> macroEntry : pageMacroCount.entrySet()) {
                Map<String, Object> serializableMacro =
                    macroMap.computeIfAbsent(macroEntry.getKey(), k -> new HashMap<>());
                String keyCount = String.format("%s_oc", space);
                serializableMacro.put(keyCount,
                    (Integer) serializableMacro.getOrDefault(keyCount, 0) + macroEntry.getValue());
                String keyPages = String.format("%s_pg", space);
                Object pagesObj = serializableMacro.computeIfAbsent(keyPages, k -> new HashSet<>());
                if (pagesObj instanceof Set) {
                    ((Set<Object>) pagesObj).add(page);
                }
            }
        }
        return macroMap;
    }

    @Override
    public void disablePrerequisites()
    {
        prerequisitesManager.disablePrerequisites();
    }

    @Override
    public void enablePrerequisites()
    {
        prerequisitesManager.enablePrerequisites();
    }
}
