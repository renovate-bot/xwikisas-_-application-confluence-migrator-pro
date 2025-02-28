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
package com.xwiki.confluencepro.test.ui;

import java.io.File;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.xwiki.test.docker.junit5.ExtensionOverride;
import org.xwiki.test.docker.junit5.TestConfiguration;
import org.xwiki.test.docker.junit5.UITest;
import org.xwiki.test.ui.TestUtils;

import com.xwiki.confluencepro.test.po.ConfluenceHomePage;
import com.xwiki.confluencepro.test.po.CreateBatchPage;
import com.xwiki.confluencepro.test.po.MigrationCreationPage;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertTrue;

@UITest(properties = {
    // Add the FileUploadPlugin which is needed by the test to upload attachment files
    "xwikiCfgPlugins=com.xpn.xwiki.plugin.fileupload.FileUploadPlugin" },
    extensionOverrides = {
        @ExtensionOverride(
            extensionId = "com.google.code.findbugs:jsr305",
            overrides = {
                "features=com.google.code.findbugs:annotations"
            }
        )
    }
)
public class ConfluenceMigratorIT
{
    private static final String MIGRATION_TITLE = "NewMigration";

    private static final String USER_NAME = "JohnDoe";

    private static final String PACKAGE_NAME = "confluence-package.zip";

    @BeforeAll
    void beforeAll(TestUtils testUtils)
    {
        testUtils.loginAsSuperAdmin();
        // The application creator needs script rights in order to execute the scripts generated by the wizard.
        testUtils.setGlobalRights("", "XWiki." + USER_NAME, "admin", true);
        testUtils.createUserAndLogin(USER_NAME, "pa$$word");
    }

    @Test
    @Order(1)
    void packageUpload(TestConfiguration testConfiguration)
    {
        ConfluenceHomePage.goToPage();
        ConfluenceHomePage confluenceHomePage = new ConfluenceHomePage();
        confluenceHomePage.lazyLoadSection("confluence-pro-tab-container-new-migration");
        confluenceHomePage.openHowToMigrateSubsection(".uploadSubsection");
        confluenceHomePage.attachFile(testConfiguration.getBrowser().getTestResourcesPath(), PACKAGE_NAME);
        assertTrue(confluenceHomePage.getPackageLiveTable().getTableLayout().countRows() > 0);
    }

    @Test
    @Order(2)
    void selectPackage()
    {
        ConfluenceHomePage.goToPage();
        ConfluenceHomePage confluenceHomePage = new ConfluenceHomePage();
        confluenceHomePage.lazyLoadSection("confluence-pro-tab-container-new-migration");
        confluenceHomePage.openHowToMigrateSubsection(".uploadSubsection");
        MigrationCreationPage migrationCreationPage = confluenceHomePage.selectPackage(1);
        migrationCreationPage.setTitle(MIGRATION_TITLE);
        migrationCreationPage.clickAdvancedMigrationOptions();
        assertTrue(migrationCreationPage.getAdvancedInputFilterProperties().size() > 1);
        assertTrue(migrationCreationPage.getAdvancedOutputProperties().size() > 1);
//         TODO: Uncomment the following lines when issue https://github.com/xwikisas/application-licensing/issues/151
//          is fixed.
//        migrationCreationPage.clickSaveAndView();
//        MigrationRunningPage runningPage = new MigrationRunningPage();
//        assertEquals(MIGRATION_TITLE, runningPage.getDocumentTitle());
//        ConfluenceHomePage.goToPage();
//        TableLayoutElement migrationsLiveTable = confluenceHomePage.getMigrationsLiveTable().getTableLayout();
//        assertEquals(1, migrationsLiveTable.countRows());
//        String migrationStatus = migrationsLiveTable
//            .getCell("Migration status", 1)
//            .getText()
//            .trim();
//        assertEquals("Waiting", migrationStatus);
    }

//    @Test
//    @Order(3)
//    void selectSpaceAndRunJob()
//    {
// TODO: Uncomment the following lines when issue https://github.com/xwikisas/application-licensing/issues/151
//  is fixed.
//        ConfluenceHomePage.goToPage();
//        ConfluenceHomePage confluenceHomePage = new ConfluenceHomePage();
//        TableLayoutElement migrationsLiveTable = confluenceHomePage.getMigrationsLiveTable().getTableLayout();
//
//        migrationsLiveTable.getCell("Migration", 1).findElement(By.cssSelector("a")).click();
//        MigrationRunningPage runningPage = new MigrationRunningPage();
//
//        QuestionSpace questionSpace = runningPage.getSelectableSpace(0);
//        questionSpace.getCheckbox().click();
//        MigrationRaportView raportView = runningPage.confirmSpacesToMigrate();
//
//        assertEquals(1, raportView.getImportedSpaces().size());
//        assertTrue(raportView.hasErrorLogs());
//    }

    /**
     * Check that all sections are loaded.
     */
    @Test
    @Order(4)
    void changeSection()
    {
        ConfluenceHomePage.goToPage();
        ConfluenceHomePage confluenceHomePage = new ConfluenceHomePage();
        confluenceHomePage.lazyLoadSection("confluence-pro-tab-container-new-migration");
        assertTrue(confluenceHomePage.checkIfSectionWasLoaded(".confluence-pro-tab-container-new-migration"));
        confluenceHomePage.lazyLoadSection("confluence-pro-tab-container-all-migrations");
        assertTrue(confluenceHomePage.checkIfSectionWasLoaded(".confluence-pro-tab-container-all-migrations"));
        confluenceHomePage.lazyLoadSection("confluence-pro-tab-container-imported-macros");
        assertTrue(confluenceHomePage.checkIfSectionWasLoaded(".confluence-pro-tab-container-imported-macros"));
        confluenceHomePage.lazyLoadSection("confluence-pro-tab-container-post-migration-fixes");
        assertTrue(confluenceHomePage.checkIfSectionWasLoaded(".confluence-pro-tab-container-post-migration-fixes"));
    }

    @Test
     /**
     * Check that the buttons work and the packages are selected.
     */
    void batchPackageSelectorButtons(TestConfiguration testConfiguration)
    {
        ConfluenceHomePage.goToPage();
        ConfluenceHomePage confluenceHomePage = new ConfluenceHomePage();
        confluenceHomePage.lazyLoadSection("confluence-pro-tab-container-new-migration");
        confluenceHomePage.openHowToMigrateSubsection(".batchSubsection");
        CreateBatchPage createBatchPage = confluenceHomePage.createNewBatch();
        createBatchPage.completePath(new File(testConfiguration.getBrowser().getTestResourcesPath()).getAbsolutePath())
            .refreshPage();
        createBatchPage.selectAll();
        assertEquals(2, createBatchPage.countSelectedPackages());
        createBatchPage.selectNone();
        assertEquals(0, createBatchPage.countSelectedPackages());
        createBatchPage.inverseSelection();
        assertEquals(2, createBatchPage.countSelectedPackages());
    }

    /**
     * Create a new batch.
     */
    @Test
    @Order(6)
    void createNewBatch(TestConfiguration testConfiguration)
    {
        ConfluenceHomePage.goToPage();
        ConfluenceHomePage confluenceHomePage = new ConfluenceHomePage();
        confluenceHomePage.lazyLoadSection("confluence-pro-tab-container-new-migration");
        confluenceHomePage.openHowToMigrateSubsection(".batchSubsection");
        CreateBatchPage createBatchPage = confluenceHomePage.createNewBatch();
        createBatchPage.completePath(new File(testConfiguration.getBrowser().getTestResourcesPath()).getAbsolutePath())
            .refreshPage().completeName("Test");
        createBatchPage.selectAll();
        createBatchPage.createBatch();
        ConfluenceHomePage.goToPage();
        confluenceHomePage.lazyLoadSection("confluence-pro-tab-container-new-migration");
        confluenceHomePage.openHowToMigrateSubsection(".batchSubsection");
        //Check that the batch was created.
        assertTrue(confluenceHomePage.countBatches() >= 1);
    }
}
