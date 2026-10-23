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
package com.xwiki.confluencepro.converters.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import com.xpn.xwiki.XWikiContext;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.confluence.filter.AbstractMacroConverter;
import org.xwiki.contrib.confluence.filter.ConfluenceFilterReferenceConverter;
import org.xwiki.contrib.confluence.filter.input.ConfluenceInputContext;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.AttachmentReference;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.WikiReference;

/**
 * Converts the inc-drawio macros.
 *
 * @version $Id$
 * @since 1.20.2
 */
@Component (hints = {"drawio", "inc-drawio"})
@Singleton
public class IncDrawIoMacroConverter extends AbstractMacroConverter
{
    private static final List<String> UNSUPPORTED_PARAMETERS = List.of("service", "diagramUrl");
    private static final String DIAGRAM_NAME = "diagramName";

    @Inject
    private Logger logger;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private ConfluenceFilterReferenceConverter converter;

    @Inject
    private ConfluenceInputContext confluenceContext;

    @Inject
    @Named("compact")
    private EntityReferenceSerializer<String> serializer;

    @Override
    public String toXWikiId(String confluenceId, Map<String, String> confluenceParameters, String confluenceContent,
        boolean inline)
    {
        return isMigratedToNative(confluenceParameters) ? "embedDiagram" : "confluence_drawio";
    }

    @Override
    protected Map<String, String> toXWikiParameters(String confluenceId, Map<String, String> confluenceParameters,
        String content)
    {
        boolean migrateToNative = isMigratedToNative(confluenceParameters);

        Map<String, String> xwikiParameters = prepareParams(confluenceParameters, migrateToNative);

        if (hasUnsupportedParameters(confluenceParameters)) {
            return xwikiParameters;
        }

        migrateDiagramName(confluenceParameters, migrateToNative, xwikiParameters);

        return xwikiParameters;
    }

    private void migrateDiagramName(Map<String, String> confluenceParameters, boolean migrateToNative,
        Map<String, String> xwikiParameters)
    {
        EntityReference currentRef = converter.convertDocumentReference(confluenceContext.getCurrentPage(), false);
        EntityReference docRef = currentRef;
        String diagramName = getDiagramName(confluenceParameters);
        String pageIdStr = confluenceParameters.get("pageId");
        if (StringUtils.isNotEmpty(pageIdStr)) {
            long pageId = Long.parseLong(pageIdStr);
            docRef = converter.convertDocumentReference(pageId, false);
            if (docRef != null && !migrateToNative) {
                xwikiParameters.put("originalDocumentRef", serializer.serialize(docRef, currentRef));
            }
        }

        if (docRef != null && migrateToNative) {
            docRef = ensureWikiParent(docRef);
            AttachmentReference a = new AttachmentReference(diagramName, new DocumentReference(docRef));
            diagramName = serializer.serialize(a, currentRef);
        }
        xwikiParameters.put(migrateToNative ? "diagramSource" : DIAGRAM_NAME, diagramName);
        if (migrateToNative) {
            xwikiParameters.put("enableEdit", "true");
        }
    }

    private EntityReference ensureWikiParent(EntityReference docRef)
    {
        if (docRef.extractReference(EntityType.WIKI) != null) {
            return docRef;
        }
        EntityReference root = confluenceContext.getProperties().getRoot();
        EntityReference wikiRef = root == null ? null : root.extractReference(EntityType.WIKI);
        if (wikiRef == null) {
            XWikiContext context = contextProvider.get();
            wikiRef = context == null ? new WikiReference("xwiki") : context.getWikiReference();
        }
        return docRef.appendParent(wikiRef);
    }

    private Map<String, String> prepareParams(Map<String, String> confluenceParameters, boolean migrateToNative)
    {
        if (migrateToNative) {
            return new LinkedHashMap<>();
        }

        for (String p : confluenceParameters.keySet()) {
            // When migrating to the bridge, we mark all the parameters as supported to avoid noisy logs since they are
            // migrated as is. Except for the ones we know we don't handle.
            if (!UNSUPPORTED_PARAMETERS.contains(p)) {
                markHandledParameter(confluenceParameters, p, true);
            }
        }

        return new LinkedHashMap<>(confluenceParameters);
    }

    private static boolean hasUnsupportedParameters(Map<String, String> confluenceParameters)
    {
        for (String unsupportedParameter : UNSUPPORTED_PARAMETERS) {
            if (confluenceParameters.containsKey(unsupportedParameter)) {
                return true;
            }
        }
        return false;
    }

    private static String getDiagramName(Map<String, String> confluenceParameters)
    {
        return StringUtils.firstNonEmpty(
            confluenceParameters.get("diagramDisplayName"),
            confluenceParameters.get(DIAGRAM_NAME)
        );
    }

    private static boolean isExtensionSupported(Map<String, String> confluenceParameters)
    {
        // If the diagram happens to end with an extension we support in our inline diagram macro,
        // we can skip the bridge.
        String n = getDiagramName(confluenceParameters);
        return (StringUtils.isNotEmpty(n) && n.endsWith(".drawio") || n.endsWith(".diagram.xml"));
    }

    private boolean isMigratedToNative(Map<String, String> confluenceParameters)
    {
        return isExtensionSupported(confluenceParameters) && !hasUnsupportedParameters(confluenceParameters);
    }

    @Override
    public InlineSupport supportsInlineMode(String id, Map<String, String> parameters, String content)
    {
        return InlineSupport.NO;
    }
}
