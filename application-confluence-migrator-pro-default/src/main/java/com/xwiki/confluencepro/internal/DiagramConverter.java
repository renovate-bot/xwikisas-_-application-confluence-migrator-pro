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
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.tools.ant.filters.StringInputStream;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.confluence.resolvers.ConfluencePageIdResolver;
import org.xwiki.contrib.confluence.resolvers.ConfluenceResolverException;
import org.xwiki.model.reference.AttachmentReference;
import org.xwiki.model.reference.AttachmentReferenceResolver;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.LocalDocumentReference;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.MacroBlock;
import org.xwiki.rendering.block.XDOM;
import org.xwiki.rendering.block.match.ClassBlockMatcher;
import org.xwiki.rendering.macro.Macro;
import org.xwiki.rendering.macro.descriptor.ContentDescriptor;
import org.xwiki.rendering.parser.Parser;
import org.xwiki.script.service.ScriptService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiAttachmentContent;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xwiki.diagram.script.DiagramScriptService;
/**
 * Confluence Reference Fixer.
 * @since 1.33.0
 * @version $Id$
 */
@Component(roles = DiagramConverter.class)
@Singleton
public class DiagramConverter
{
    private static final ClassBlockMatcher MACRO_MATCHER = new ClassBlockMatcher(MacroBlock.class);
    private static final String DIAGRAM_CLASS = "DiagramClass";
    private static final String FAILED_TO_UPDATE_DIAGRAM = "Document [{}]: failed to update diagram";
    private static final String DRAWIO = "drawio";
    private static final String GLIFFY = "gliffy";

    private static final String[] DIAGRAM_MACRO_NAMES = { GLIFFY, DRAWIO };

    private static final EntityReference DIAGRAM_CLASS_REFERENCE =
        new LocalDocumentReference("Diagram", DIAGRAM_CLASS);

    private static final EntityReference DIAGRAM_PRO_CLASS_REFERENCE =
        new LocalDocumentReference(List.of("Confluence", "Macros"), DIAGRAM_CLASS);

    private static final TypeReference<Map<String, Map<String, Integer>>> MACRO_PAGES_TYPE_REF =
        new TypeReference<Map<String, Map<String, Integer>>>() { };

    private static final String CONFLUENCE_UNDERSCORE = "confluence_";
    private static final String DIAGRAM_NAME = "diagramName";
    private static final String DRAWIO_EXT = ".drawio";
    private static final String DIAGRAM_XML_EXT = ".diagram.xml";
    private static final String PNG_EXT = ".png";
    private static final String AT = "@";

    @Inject
    private MigrationFixingTools migrationFixingTools;

    @Inject
    private Logger logger;

    @Inject
    private Provider<ComponentManager> componentManagerProvider;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private DocumentReferenceResolver<String> documentResolver;

    @Inject
    private AttachmentReferenceResolver<String> attachmentResolver;

    @Inject
    @Named("compact")
    private EntityReferenceSerializer<String> localSerializer;

    @Inject
    @Named("diagram")
    private ScriptService diagramService;

    @Inject
    private ConfluencePageIdResolver pageIdResolver;

    /**
     * Fix broken references in all documents of the given space.
     * @param migrationReferences the migration documents
     * @param spaceReferences the spaces in which to fix the references
     * @param updateInPlace whether to update migrated documents with the fixed references in place instead of
     *                      creating a new revision.
     * @param dryRun true to simulate only (the fixed documents will not be saved)
     * @return the statistics of the reference fixing sessions
     */
    public MigrationFixingStats fixDocuments(List<EntityReference> migrationReferences,
        List<EntityReference> spaceReferences, boolean updateInPlace, boolean dryRun)
    {
        Stats s = new Stats();
        migrationFixingTools.fixDocuments(
            s,
            migrationReferences,
            spaceReferences,
            migratedDoc -> fixDocument(s, migratedDoc, updateInPlace, dryRun),
            migrationDoc -> fixDocumentsOfMigration(s, migrationDoc, updateInPlace, dryRun)
        );
        return s;
    }

    private void fixDocumentsOfMigration(Stats s, XWikiDocument migrationDoc, boolean updateInPlace, boolean dryRun)
    {
        Map<String, Map<String, Integer>> macroPages = null;
        XWikiAttachment macroPagesAttachment = migrationDoc.getExactAttachment("macroPages.json");
        if (macroPagesAttachment != null) {
            XWikiAttachmentContent attachmentContent = null;
            try {
                attachmentContent = macroPagesAttachment.getAttachmentContent(contextProvider.get());
            } catch (XWikiException e) {
                logger.error("Failed get macro pages data", e);
            }
            if (attachmentContent != null) {
                try {
                    InputStream contentInputStream = attachmentContent.getContentInputStream();
                    macroPages = new ObjectMapper().readValue(contentInputStream, MACRO_PAGES_TYPE_REF);
                } catch (IOException e) {
                    logger.error("Failed to read macro pages", e);
                }
            }
        }

        if (macroPages == null) {
            logger.warn("Failed to find the list of affected pages in macroPages.json, will browse all the documents");
            migrationFixingTools.fixDocumentsOfMigration(migrationDoc,
                migratedDoc -> fixDocument(s, migratedDoc, updateInPlace, dryRun));
        } else {
            List<String> docs = new ArrayList<>();
            for (Map.Entry<String, Map<String, Integer>> macroPageEntry : macroPages.entrySet()) {
                Map<String, Integer> macroInfo = macroPageEntry.getValue();
                if (hasDiagram(macroInfo)) {
                    docs.add(macroPageEntry.getKey());
                }
            }
            migrationFixingTools.fixDocuments(docs, migratedDoc -> fixDocument(s, migratedDoc, updateInPlace, dryRun));
        }
    }

    private static boolean hasDiagram(Map<String, Integer> macroInfo)
    {
        for (String macroName : DIAGRAM_MACRO_NAMES) {
            if (macroInfo.getOrDefault(CONFLUENCE_UNDERSCORE + macroName, 0) > 0
                || macroInfo.getOrDefault(macroName, 0) > 0) {
                return true;
            }
        }
        return false;
    }

    private static XDOM parse(ComponentManager componentManager, String text, String syntaxId)
    {
        XDOM result;
        try {
            Parser parser = componentManager.getInstance(Parser.class, syntaxId);
            result = parser.parse(new StringReader(text));
        } catch (Exception e) {
            result = null;
        }
        return result;
    }

    /**
     * @param macroBlock the macro to parse
     * @param syntaxId the syntax of the document
     * @return return the XDOM content of the given macro
     * @throws ComponentLookupException if something goes wrong when looking up the macro
     * @since 1.22.0
     */
    private XDOM getMacroXDOM(MacroBlock macroBlock, String syntaxId)
        throws ComponentLookupException
    {
        ComponentManager componentManager = componentManagerProvider.get();
        if (componentManager.hasComponent(Macro.class, macroBlock.getId())) {
            ContentDescriptor macroContentDescriptor =
                ((Macro<?>) componentManager.getInstance(Macro.class, macroBlock.getId()))
                    .getDescriptor()
                    .getContentDescriptor();

            if (macroContentDescriptor != null && macroContentDescriptor.getType().equals(Block.LIST_BLOCK_TYPE)
                && StringUtils.isNotBlank(macroBlock.getContent()))
            {
                return parse(componentManager, macroBlock.getContent(), syntaxId);
            }
        } else if (StringUtils.isNotBlank(macroBlock.getContent())) {
            // Just assume that the macro content is wiki syntax if we don't know the macro.
            logger.debug("Calling parse on unknown macro [{}] with syntax [{}]", macroBlock.getId(), syntaxId);
            return parse(componentManager, macroBlock.getContent(), syntaxId);
        }
        return null;
    }

    private boolean convertDiagramMacros(Stats s, XWikiDocument migratedDoc, XDOM xdom, String syntaxId, boolean dryRun)
    {
        boolean updated = false;
        List<MacroBlock> macros = xdom.getBlocks(MACRO_MATCHER, Block.Axes.DESCENDANT_OR_SELF);
        for (MacroBlock macroBlock : macros) {
            try {
                if (shouldConvertMacro(macroBlock)) {
                    updated = convertDiagramMacro(s, migratedDoc, dryRun, macroBlock) || updated;
                } else {
                    XDOM macroXDOM = getMacroXDOM(macroBlock, syntaxId);
                    if (macroXDOM != null) {
                        updated = convertDiagramMacros(s, migratedDoc, macroXDOM, syntaxId, dryRun) || updated;
                    }
                }
            } catch (ComponentLookupException e) {
                logger.error("Component lookup error trying to find the diagram macro", e);
            }
        }
        return updated;
    }

    private static boolean shouldConvertMacro(MacroBlock macroBlock)
    {
        for (String macroName : DIAGRAM_MACRO_NAMES) {
            String id = macroBlock.getId();
            if ((CONFLUENCE_UNDERSCORE + macroName).equals(id) || macroName.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private boolean convertDiagramMacro(Stats s, XWikiDocument migratedDoc, boolean dryRun, MacroBlock macroBlock)
    {
        boolean updated = false;
        try {
            if (convertDiagramMacro(migratedDoc, macroBlock, dryRun)) {
                updated = true;
                s.incSuccessfulDiagrams();
            } else {
                logger.error(FAILED_TO_UPDATE_DIAGRAM, migratedDoc.getDocumentReference());
                s.incFailedDiagrams();
            }
        } catch (XWikiException e) {
            logger.error(FAILED_TO_UPDATE_DIAGRAM, migratedDoc.getDocumentReference(), e);
            s.incFailedDiagrams();
        }
        return updated;
    }

    private boolean convertDiagramMacro(XWikiDocument migratedDoc, MacroBlock macroBlock, boolean dryRun)
        throws XWikiException
    {
        XWikiAttachment diagramAttachment = getDiagramAttachment(migratedDoc, macroBlock);
        if (diagramAttachment == null) {
            return false;
        }

        String id = macroBlock.getId();
        if (!id.endsWith(DRAWIO)) {
            // Convert gliffy to drawio.
            String originalDiagramName = diagramAttachment.getFilename();
            String diagramName = originalDiagramName + DRAWIO_EXT;

            XWikiDocument diagramDoc = diagramAttachment.getDoc();
            XWikiAttachment newAttachment = diagramDoc.getExactAttachment(diagramName);
            if (newAttachment == null) {
                // if the diagram is not already converted to drawio in the containing document, convert it
                String diagramContent = getDiagramContent(migratedDoc, diagramName, id, diagramAttachment);
                if (StringUtils.isEmpty(diagramContent)) {
                    return false;
                }
                if (dryRun) {
                    return true;
                }
                try {
                    XWikiContext context = contextProvider.get();
                    StringInputStream content = new StringInputStream(diagramContent);
                    diagramAttachment = diagramDoc.setAttachment(diagramName, content, context);
                    maybeMovePNG(diagramDoc, originalDiagramName, diagramName, dryRun);
                    if (diagramDoc != migratedDoc) {
                        context.getWiki().saveDocument(diagramDoc,
                                "Migrate diagram [" + originalDiagramName + "] to drawio", context);
                    }
                } catch (IOException e) {
                    logger.warn("Converted diagram content for diagram [{}] could not be attached, skipping",
                            diagramName);
                    return false;
                }
            } else {
                diagramAttachment = newAttachment;
            }
        }

        EntityReference migratedDocRef = migratedDoc.getDocumentReference();
        try {
            diagramAttachment = maybeMoveDiagram(migratedDoc, diagramAttachment, dryRun);
        } catch (IOException | XWikiException e) {
            logger.warn("Document [{}]: Could not rename diagram [{}] in document [{}]",
                migratedDocRef, diagramAttachment.getFilename(), diagramAttachment.getDoc().getDocumentReference());
        }

        String diagramSource = getDiagramSource(diagramAttachment, migratedDocRef);
        macroBlock.getParent().replaceChild(
            List.of(new MacroBlock(
                "embedDiagram",
                Map.of("diagramSource", diagramSource, "enableEdit", "true"),
                macroBlock.isInline())),
            macroBlock);
        return true;
    }

    private String getDiagramSource(XWikiAttachment diagramAttachment, EntityReference migratedDocRef)
    {
        String diagramSource = localSerializer.serialize(diagramAttachment.getReference(), migratedDocRef);
        String filename = diagramAttachment.getFilename();
        if (StringUtils.countMatches(diagramSource, AT) == StringUtils.countMatches(filename, AT)) {
            // embedDiagram doesn't understand a diagramSource with the raw filename. For diagrams attached to the same
            // page, we need a leading "@" to force embedDiagram to consider the source as an attachment.
            return AT + diagramSource;
        }
        return diagramSource;
    }

    private XWikiAttachment getDiagramAttachment(XWikiDocument migratedDoc, MacroBlock macroBlock)
    {
        String diagramName = StringUtils.defaultString(StringUtils.firstNonEmpty(
                macroBlock.getParameter(DIAGRAM_NAME),
                macroBlock.getParameter("name")));

        String pageid = macroBlock.getParameter("pageid");
        String originalDocumentRef = macroBlock.getParameter("originalDocumentRef");
        XWikiAttachment diagramAttachment = getDiagramAttachment(migratedDoc, diagramName, pageid, originalDocumentRef);
        if (diagramAttachment == null) {
            logger.warn("Document [{}]: Diagram attachment [{}] not found, skipping",
                migratedDoc.getDocumentReference(), diagramName);
        }
        return diagramAttachment;
    }

    private boolean maybeMovePNG(XWikiDocument diagramDoc, String originalDiagramName, String diagramName,
        boolean dryRun) throws IOException, XWikiException
    {
        String pngName = originalDiagramName + PNG_EXT;
        XWikiAttachment png = diagramDoc.getExactAttachment(pngName);
        if (png != null) {
            String newPngName = StringUtils.removeEnd(diagramName, DRAWIO_EXT) + PNG_EXT;
            if (pngName.equals(newPngName)) {
                // Nothing to do, the png file already has the correct name.
                return false;
            }
            if (dryRun) {
                logger.info("Would rename PNG attachment [{}] to [{}] in [{}]",
                        pngName, newPngName, diagramDoc.getDocumentReference());
                return true;
            }
            moveAttachment(png, newPngName);
            return true;
        }

        return false;
    }

    // inspired of org.xwiki.attachment.internal.refactoring.job.MoveAttachmentJob#addAttachment
    private XWikiAttachment moveAttachment(XWikiAttachment a, String newName) throws IOException, XWikiException
    {
        XWikiDocument d = a.getDoc();
        d.removeAttachment(a, false);
        XWikiAttachment newAttachment = a.clone(newName, contextProvider.get());
        newAttachment.setDoc(d);
        d.setAttachment(newAttachment);
        return newAttachment;
    }

    private XWikiAttachment maybeMoveDiagram(XWikiDocument migratedDoc, XWikiAttachment diagramAttachment,
        boolean dryRun) throws IOException, XWikiException
    {
        String diagramFilename = diagramAttachment.getFilename();
        if (hasSupportedExtension(diagramFilename)) {
            if (maybeMovePNG(diagramAttachment.getDoc(), diagramFilename, diagramFilename, dryRun)) {
                saveDiagramDoc(migratedDoc, dryRun, diagramAttachment.getDoc(), diagramFilename);
            }
            return diagramAttachment;
        }

        XWikiAttachment newDiagramAttachment = diagramAttachment;
        String newDiagramName = diagramFilename + DRAWIO_EXT;
        if (dryRun) {
            logger.info("Document [{}]: Would rename diagram [{}] to [{}] in document [{}]",
                migratedDoc.getDocumentReference(), diagramFilename, newDiagramName,
                    diagramAttachment.getDoc().getDocumentReference());
        } else {
            newDiagramAttachment = moveAttachment(diagramAttachment, newDiagramName);
        }
        XWikiDocument diagramDoc = newDiagramAttachment.getDoc();
        maybeMovePNG(diagramDoc, diagramFilename, newDiagramName, dryRun);
        saveDiagramDoc(migratedDoc, dryRun, diagramDoc, newDiagramName);
        return newDiagramAttachment;
    }

    private void saveDiagramDoc(XWikiDocument migratedDoc, boolean dryRun, XWikiDocument diagramDoc, String name)
    {
        if (dryRun || diagramDoc == migratedDoc) {
            // We don't save the attachment document if it's the same as the migrated document because the
            // migrated document will be saved later.
            return;
        }

        XWikiContext context = contextProvider.get();
        try {
            context.getWiki().saveDocument(diagramDoc,
                "Renamed diagram and/or PNG of [" + name + "] so it can be used in an inlineDiagram macro",
                context);
        } catch (XWikiException e) {
            logger.warn("Diagram[{}] could not be renamed, skipping", name);
        }
    }

    private static boolean hasSupportedExtension(String diagramName)
    {
        return diagramName.endsWith(DRAWIO_EXT) || diagramName.endsWith(DIAGRAM_XML_EXT);
    }

    private String getDiagramContent(XWikiDocument migratedDoc, String diagramName, String macroName,
        XWikiAttachment diagramAttach)
    {
        String diagramContent;
        try {
            XWikiAttachmentContent attachmentContent = diagramAttach.getAttachmentContent(contextProvider.get());
            InputStream attachmentInputStream = attachmentContent.getContentInputStream();
            diagramContent = new String(attachmentInputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (XWikiException | IOException e) {
            logger.error("Document [{}]: failed to get diagram attachment [{}] on document [{}]",
                migratedDoc.getDocumentReference(), diagramName, diagramAttach.getDoc().getDocumentReference());
            return null;
        }

        if (diagramContent.isEmpty()) {
            logger.error("Document [{}]: diagram attachment [{}] on document [{}] is empty",
                migratedDoc.getDocumentReference(), diagramAttach.getDoc().getDocumentReference(), diagramName);
        } else if (!macroName.endsWith(DRAWIO)) {
            try {
                return ((DiagramScriptService) diagramService).importDiagram(diagramContent, diagramName);
            } catch (Exception e) {
                // It can be a com.google.gson.JsonSyntaxException / java.lang.NumberFormatException because our
                // conversion code is old and doesn't manage newly created diagrams well
                logger.error("Diagram conversion threw an exception", e);
                return null;
            }
        }
        return diagramContent;
    }

    private XWikiAttachment getDiagramAttachment(XWikiDocument migratedDoc, String diagramName, String pageIdStr,
         String originalDocumentRef)
    {
        try {
            if (StringUtils.isNotEmpty(originalDocumentRef)) {
                return getDiagramAttachment(migratedDoc, diagramName, documentResolver.resolve(originalDocumentRef));
            }
            if (StringUtils.isNotEmpty(pageIdStr)) {
                EntityReference docRef = pageIdResolver.getDocumentById(Long.parseLong(pageIdStr, 10));
                if (docRef == null) {
                    logger.error("Document [{}]: could not find page id [{}] for diagram [{}]",
                        migratedDoc.getDocumentReference(), pageIdStr, diagramName);
                    return null;
                } else {
                    return getDiagramAttachment(migratedDoc, diagramName, docRef);
                }
            }
            AttachmentReference attachRef = attachmentResolver.resolve(diagramName, migratedDoc.getDocumentReference());
            XWikiContext context = contextProvider.get();
            XWikiDocument attachmentDoc = migratedDoc.getDocumentReference().equals(attachRef.getDocumentReference())
                    ? migratedDoc
                    : context.getWiki().getDocument(attachRef.getDocumentReference(), context).clone();
            return getDiagramAttachment(migratedDoc, diagramName, attachmentDoc);
        } catch (XWikiException e) {
            logger.error("Document [{}]: could get document for diagram [{}]",
                migratedDoc.getDocumentReference(), diagramName);
        } catch (NumberFormatException e) {
            logger.error("Document [{}]: could not parse page id [{}] for diagram [{}]",
                migratedDoc.getDocumentReference(), pageIdStr, diagramName);
        } catch (ConfluenceResolverException e) {
            logger.error("Document [{}]: failed to resolve page id [{}] for diagram [{}]",
                migratedDoc.getDocumentReference(), pageIdStr, diagramName, e);
        }

        return null;
    }

    private XWikiAttachment getDiagramAttachment(XWikiDocument migratedDoc, String diagramName,
            EntityReference docRef) throws XWikiException
    {
        XWikiContext context = contextProvider.get();
        XWikiDocument docContainingDiagram = context.getWiki().getDocument(docRef, context).clone();
        return getDiagramAttachment(migratedDoc, diagramName, docContainingDiagram);
    }

    private XWikiAttachment getDiagramAttachment(XWikiDocument migratedDoc, String diagramName,
        XWikiDocument docContainingDiagram)
    {
        if (docContainingDiagram.isNew()) {
            logger.error("Document [{}]: the document [{}] referenced for diagram [{}] is new",
                    migratedDoc, docContainingDiagram.getDocumentReference(), diagramName);
            return null;
        }
        XWikiAttachment attachment = docContainingDiagram.getExactAttachment(diagramName);
        if (attachment == null && !diagramName.endsWith(DRAWIO_EXT)) {
            // maybe the attachment was already renamed to .drawio during a former macro conversion?
            attachment = docContainingDiagram.getExactAttachment(diagramName + DRAWIO_EXT);
        }
        return attachment;
    }

    private void fixDocument(Stats s, XWikiDocument migratedDoc, boolean updateInPlace, boolean dryRun)
    {
        XDOM xdom = migratedDoc.getXDOM();
        boolean updated = convertDiagramMacros(s, migratedDoc, xdom, migratedDoc.getSyntax().toIdString(), dryRun);
        if (updated && !dryRun) {
            try {
                migratedDoc.setContent(xdom);
            } catch (XWikiException e) {
                logger.error("Failed to update the document XDOM [{}]", migratedDoc.getDocumentReference(), e);
                s.incFailedDocs();
                return;
            }
        }
        migrationFixingTools.handleDocumentUpdate(s, migratedDoc, updated, updateInPlace, dryRun,
            "Convert Confluence diagrams");
    }

    private static final class Stats implements MigrationFixingStats
    {
        private int failedDocs;
        private int successfulDocs;
        private int unchangedDocs;
        private int successfulDiagrams;
        private int failedDiagrams;

        @Override
        public void incFailedDocs()
        {
            failedDocs++;
        }

        @Override
        public void incSuccessfulDocs()
        {
            successfulDocs++;
        }

        @Override
        public void incUnchangedDocs()
        {
            unchangedDocs++;
        }

        void incSuccessfulDiagrams()
        {
            successfulDiagrams++;
        }

        void incFailedDiagrams()
        {
            failedDiagrams++;
        }

        @Override
        public String toJSON()
        {
            return "{\"failedDocs\": " + failedDocs
                + ", \"successfulDocs\": " + successfulDocs
                + ", \"unchangedDocs\": " + unchangedDocs
                + ", \"successfulDiagrams\": " + successfulDiagrams
                + ", \"failedDiagrams\": " + failedDiagrams
                + "}";
        }
    }
}
