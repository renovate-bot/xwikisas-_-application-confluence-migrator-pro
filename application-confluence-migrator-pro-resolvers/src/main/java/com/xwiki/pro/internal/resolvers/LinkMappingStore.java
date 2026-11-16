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
package com.xwiki.pro.internal.resolvers;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.store.DatabaseProduct;
import com.xpn.xwiki.store.XWikiHibernateStore;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.util.Collection;
import java.util.Optional;

/**
 * Link mapping store. For a brief period of time, we used to store link mapping in SQL tables. We don't do this
 * anymore, and this code is only there to keep this old store working (resolving Confluence stuff would start failing
 * on the concerned wiki if we stopped doing this). The code to create these tables and to add entries was removed.
 *
 * @since 1.28.0
 * @version $Id$
 */
@Component (roles = LinkMappingStore.class)
@Singleton
public class LinkMappingStore
{
    private static final String TABLE_BY_TITLE = "confluencepro_linkmapping_by_title";
    private static final String TABLE_BY_ID = "confluencepro_linkmapping_by_id";
    private static final String DELETE_FROM = "delete from ";
    private static final String SELECT_REFERENCE_FROM = "select reference from ";
    private static final String WHERE_PAGE_ID = " where pageId = ?";
    private static final String WHERE_SPACE_KEY_AND_PAGE_TITLE = " where spaceKey = ? and pageTitle = ?";
    private static final String DROP_TABLE = "drop table ";
    private static final String SELECT_1_FROM = "select 1 from ";
    private static final String LIMIT_1 = " limit 1";
    private static final String SPACES = "spaces";

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Logger logger;

    private boolean initialized;

    private boolean dontExist;

    private boolean areTableAbsent()
    {
        if (!isSupported() || dontExist) {
            return true;
        }

        boolean res;
        Session session = beginTransaction();
        try {
            res = areTableAbsent(session);
        } finally {
            endTransaction(false);
        }
        return res;
    }

    private boolean areTableAbsent(Session session)
    {
        if (!isSupported()) {
            return true;
        }

        if (dontExist) {
            return true;
        }

        if (initialized) {
            return false;
        }

        boolean found = session.createNativeQuery(
                "select 1 from information_schema.tables where lower(table_name) = '" + TABLE_BY_ID + "' "
                    + "and lower(table_schema) in ('xwiki', 'app', 'public')")
                .getResultStream().findFirst().isPresent();
        // we want to check that the existing table is only on the main wiki. On PostgreSQL, table_schema is set to
        // public. On database not based on catalogs, the schema is xwiki.
        // We need to do this because former versions of Confluence Migrator Pro incorrectly created the table in
        // subwikis under certain conditions, including when running it on a subwiki.

        if (found) {
            initialized = true;
            return false;
        }
        dontExist = true;
        return true;
    }

    /**
     * Begin a hibernate transaction.
     * @return a Session
     */
    public Session beginTransaction()
    {
        if (!isSupported()) {
            return null;
        }

        XWikiContext context = contextProvider.get();
        try {
            XWikiHibernateStore store = XWiki.getMainXWiki(context).getHibernateStore();
            if (store.beginTransaction(context)) {
                return store.getSession(context);
            }
        } catch (XWikiException e) {
            logger.error("Failed to begin a transaction", e);
        }
        return null;
    }

    /**
     * End the current hibernate transaction.
     * @param commit whether the transaction should be committed
     */
    public void endTransaction(boolean commit)
    {
        if (!isSupported()) {
            return;
        }

        XWikiContext context = contextProvider.get();
        XWikiHibernateStore store = context.getWiki().getHibernateStore();
        store.endTransaction(context, commit);
    }

    String get(long pageId)
    {
        if (areTableAbsent()) {
            return null;
        }

        String ref;
        Session session = beginTransaction();
        try {
            ref = getOneString(
                session.createNativeQuery(SELECT_REFERENCE_FROM + TABLE_BY_ID + WHERE_PAGE_ID)
                    .setParameter(1, pageId));
        } finally {
            endTransaction(false);
        }
        return ref;
    }

    String get(String spaceKey, String pageTitle)
    {
        if (areTableAbsent()) {
            return null;
        }

        String ref;
        Session session = beginTransaction();
        try {
            ref = getOneString(session.createNativeQuery(
                    SELECT_REFERENCE_FROM + TABLE_BY_TITLE + WHERE_SPACE_KEY_AND_PAGE_TITLE)
                .setParameter(1, spaceKey)
                .setParameter(2, pageTitle));
        } finally {
            endTransaction(false);
        }
        return ref;
    }

    String getSpaceForReference(String reference)
    {
        if (areTableAbsent()) {
            return null;
        }

        String ref;
        Session session = beginTransaction();
        try {
            ref = getOneString(session.createNativeQuery(
                    "select spaceKey from " + TABLE_BY_TITLE + " where reference = ?")
                .setParameter(1, reference));
        } finally {
            endTransaction(false);
        }
        return ref;
    }

    String getShortestReferenceForSpace(String spaceKey)
    {
        Session session = beginTransaction();
        try {
            if (areTableAbsent(session)) {
                return null;
            }

            return getOneString(session.createNativeQuery(
                    "select reference from (select reference from " + TABLE_BY_TITLE
                        + " where spaceKey = ? order by length(reference) asc limit 1) sub")
                .setParameter(1, spaceKey));
        } finally {
            endTransaction(false);
        }
    }

    String getShortestReferenceForSpaceByReference(String reference)
    {
        if (areTableAbsent()) {
            return null;
        }

        String ref;
        Session session = beginTransaction();
        try {
            ref = getOneString(session.createNativeQuery(
                    SELECT_REFERENCE_FROM + TABLE_BY_TITLE + " where spaceKey in (select spaceKey from "
                        + TABLE_BY_TITLE + " where reference = ? limit 1) order by length(reference) asc limit 1")
                .setParameter(1, reference));
        } finally {
            endTransaction(false);
        }
        return ref;
    }

    private static String getOneString(org.hibernate.query.Query<?> q)
    {
        Optional<?> res = q.getResultStream().findFirst();
        if (res.isPresent()) {
            Object r = res.get();
            if (r instanceof String) {
                return (String) r;
            }
        }
        return null;
    }

    /**
     * Remove spaces from the link mapping. This is useful when the information is elsewhere, for instance in
     * ConfluencePageClass objects.
     * @param spaces the space to remove from the link mapping.
     */
    public void removeSpaces(Collection<String> spaces)
    {
        if (areTableAbsent()) {
            return;
        }

        Session session = beginTransaction();
        try {
            session.createNativeQuery(
                    DELETE_FROM + TABLE_BY_ID + " where reference in (select reference from " + TABLE_BY_TITLE
                        + " where spaceKey in (:spaces))")
                .setParameterList(SPACES, spaces)
                .executeUpdate();

            session.createNativeQuery(DELETE_FROM + TABLE_BY_TITLE + " where spaceKey in (:spaces)")
                .setParameter(SPACES, spaces)
                .executeUpdate();

            maybeDropTables(session);
        } finally {
            endTransaction(true);
        }
    }

    /**
     * If there is no link mapping stored in the tables, remove them. This makes resolving Confluence pages faster
     * and helps clean up temporary data that should not be kept in the wiki forever.
     * @param session the session to use
     */
    private void maybeDropTables(Session session)
    {
        if (session.createNativeQuery(SELECT_1_FROM + TABLE_BY_ID + LIMIT_1)
            .getResultStream()
            .findFirst()
            .isPresent()) {
            return;
        }

        if (session.createNativeQuery(SELECT_1_FROM + TABLE_BY_TITLE + LIMIT_1)
            .getResultStream()
            .findFirst()
            .isPresent()) {
            return;
        }

        empty(session);
    }

    /**
     * Drop the link mapping tables.
     */
    public void empty()
    {
        Session session = beginTransaction();
        try {
            empty(session);
        } finally {
            endTransaction(true);
        }
    }

    /**
     * @return the number of entries.
     */
    public long getEntryCount()
    {
        long res = 0;
        Session session = beginTransaction();
        try {
            if (!areTableAbsent(session)) {
                res += getCount(session, TABLE_BY_ID);
                res += getCount(session, TABLE_BY_TITLE);
            }
        } finally {
            endTransaction(true);
        }
        return res;
    }

    private long getCount(Session session, String table)
    {
        Optional<?> r = session.createNativeQuery("select count(reference) from " + table)
            .getResultStream()
            .findFirst();
        if (r.isPresent()) {
            Object res = r.get();
            if (res instanceof Number) {
                return ((Number) res).longValue();
            }
        }
        logger.error("Could not count entries in " + TABLE_BY_ID);
        return 0;
    }

    private void empty(Session session)
    {
        session.createNativeQuery(DROP_TABLE + TABLE_BY_ID).executeUpdate();
        session.createNativeQuery(DROP_TABLE + TABLE_BY_TITLE).executeUpdate();

        dontExist = true;
    }

    /**
     * @return whether the link mapping store can run on this setup. Notably, Oracle database is not supported.
     */
    public boolean isSupported()
    {
        DatabaseProduct p = contextProvider.get().getWiki().getHibernateStore().getDatabaseProductName();
        return !p.equals(DatabaseProduct.ORACLE);
    }
}
