package com.cedarsoftware.ncube
import com.cedarsoftware.ncube.formatters.JsonFormatter
import com.cedarsoftware.util.IOUtilities
import com.cedarsoftware.util.SafeSimpleDateFormat
import com.cedarsoftware.util.StringUtilities
import com.cedarsoftware.util.UniqueIdGenerator
import groovy.sql.Sql
import groovy.transform.CompileStatic

import java.sql.Blob
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.zip.GZIPOutputStream
/**
 * SQL Persister for n-cubes.  Manages all reads and writes of n-cubes to an SQL database.
 *
 * @author John DeRegnaucourt (jdereg@gmail.com)
 *         <br>
 *         Copyright (c) Cedar Software LLC
 *         <br><br>
 *         Licensed under the Apache License, Version 2.0 (the "License")
 *         you may not use this file except in compliance with the License.
 *         You may obtain a copy of the License at
 *         <br><br>
 *         http://www.apache.org/licenses/LICENSE-2.0
 *         <br><br>
 *         Unless required by applicable law or agreed to in writing, software
 *         distributed under the License is distributed on an "AS IS" BASIS,
 *         WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *         See the License for the specific language governing permissions and
 *         limitations under the License.
 */
@CompileStatic
class NCubeJdbcPersister
{
    static final SafeSimpleDateFormat dateTimeFormat = new SafeSimpleDateFormat('yyyy-MM-dd HH:mm:ss')
    static final String CUBE_VALUE_BIN = 'cube_value_bin'
    static final String TEST_DATA_BIN = 'test_data_bin'
    static final String NOTES_BIN = 'notes_bin'
    static final String HEAD_SHA_1 = 'head_sha1'
    private static final long EXECUTE_BATCH_CONSTANT = 35

    List<NCubeInfoDto> search(Connection c, ApplicationID appId, String cubeNamePattern, String searchContent, Map<String, Object> options)
    {
        List<NCubeInfoDto> list = new ArrayList<>()
        Pattern searchPattern = null

        if (StringUtilities.hasContent(searchContent))
        {
            options.put(NCubeManager.SEARCH_INCLUDE_CUBE_DATA, true)
            searchPattern = Pattern.compile(convertPattern(searchContent), Pattern.CASE_INSENSITIVE)
        }

        runSelectCubesStatement(c, appId, cubeNamePattern, options, { ResultSet row -> getCubeInfoRecords(appId, searchPattern, list, row) })
        return list
    }

    NCube loadCube(Connection c, ApplicationID appId, String cubeName)
    {
        Map<String, Object> options = [(NCubeManager.SEARCH_ACTIVE_RECORDS_ONLY): true,
                                       (NCubeManager.SEARCH_INCLUDE_CUBE_DATA): true,
                                       (NCubeManager.SEARCH_EXACT_MATCH_NAME): true] as Map

        NCube cube = null
        runSelectCubesStatement(c, appId, cubeName, options, { ResultSet row ->
            if (cube != null)
            {
                throw new IllegalStateException('More than one cube found with name: ' + cubeName + ', app: ' + appId)
            }
            cube = buildCube(appId, row)
        })
        return cube
    }

    NCube loadCubeById(Connection c, long cubeId)
    {
        Map map = [id: cubeId]
        Sql sql = new Sql(c)
        NCube cube = null
        sql.eachRow(map, "SELECT n_cube_nm, tenant_cd, app_cd, version_no_cd, status_cd, revision_number, branch_id, cube_value_bin, changed, sha1, head_sha1 FROM n_cube where n_cube_id = :id", 0, 1, { ResultSet row ->
            String tenant = row.getString('tenant_cd')
            String status = row.getString('status_cd')
            String app = row.getString('app_cd')
            String version = row.getString('version_no_cd')
            String branch = row.getString('branch_id')
            ApplicationID appId = new ApplicationID(tenant.trim(), app, version, status, branch)
            cube = buildCube(appId, row)
        })
        if (cube)
        {
            return cube
        }
        throw new IllegalArgumentException("Unable to find cube with id: " + cubeId)
    }

    NCube loadCubeBySha1(Connection c, ApplicationID appId, String cubeName, String sha1)
    {
        Map map = appId as Map
        map.putAll([cube: buildName(c, cubeName), sha1: sha1])
        NCube cube = null

        new Sql(c).eachRow(map, "SELECT n_cube_id, n_cube_nm, app_cd, version_no_cd, status_cd, revision_number, branch_id, cube_value_bin, test_data_bin, notes_bin, changed, sha1, head_sha1, create_dt " +
                "FROM n_cube " +
                "WHERE " + buildNameCondition(c, "n_cube_nm") + " = :cube AND app_cd = :app AND version_no_cd = :version AND status_cd = :status AND tenant_cd = RPAD(:tenant, 10, ' ') AND branch_id = :branch AND sha1 = :sha1 " +
                "ORDER BY abs(revision_number) DESC", 0, 1, { ResultSet row ->
                cube = buildCube(appId, row)
        })
        if (cube)
        {
            return cube
        }
        throw new IllegalArgumentException('Unable to find cube: ' + cubeName + ', app: ' + appId + ' with SHA-1: ' + sha1)
    }

    List<NCubeInfoDto> getRevisions(Connection c, ApplicationID appId, String cubeName)
    {
        List<NCubeInfoDto> records = new ArrayList<>()
        Map map = appId as Map
        map.cube = buildName(c, cubeName)
        Sql sql = new Sql(c)
        sql.eachRow(map, "SELECT n_cube_id, n_cube_nm, notes_bin, version_no_cd, status_cd, app_cd, create_dt, create_hid, revision_number, branch_id, cube_value_bin, sha1, head_sha1, changed " +
                "FROM n_cube " +
                "WHERE " + buildNameCondition(c, "n_cube_nm") + " = :cube AND app_cd = :app AND version_no_cd = :version AND tenant_cd = RPAD(:tenant, 10, ' ') AND status_cd = :status AND branch_id = :branch " +
                "ORDER BY abs(revision_number) DESC", {   ResultSet row -> getCubeInfoRecords(appId, null, records, row) })

        if (records.isEmpty())
        {
            throw new IllegalArgumentException("Cannot fetch revision history for cube: " + cubeName + " as it does not exist in app: " + appId)
        }
        return records
    }

    NCubeInfoDto insertCube(Connection c, ApplicationID appId, String name, Long revision, byte[] cubeData, byte[] testData, String notes, boolean changed, String sha1, String headSha1, long time, String username) throws SQLException
    {
        PreparedStatement s = c.prepareStatement("""\
INSERT INTO n_cube (n_cube_id, tenant_cd, app_cd, version_no_cd, status_cd, branch_id, n_cube_nm, revision_number,
 sha1, head_sha1, create_dt, create_hid, cube_value_bin, test_data_bin, notes_bin, changed)
 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        long uniqueId = UniqueIdGenerator.getUniqueId()
        s.setLong(1, uniqueId)
        s.setString(2, appId.tenant)
        s.setString(3, appId.app)
        s.setString(4, appId.version)
        s.setString(5, appId.status)
        s.setString(6, appId.branch)
        s.setString(7, name)
        s.setLong(8, revision)
        s.setString(9, sha1)
        s.setString(10, headSha1)
        Timestamp now = new Timestamp(time)
        s.setTimestamp(11, now)
        s.setString(12, username)
        s.setBytes(13, cubeData)
        s.setBytes(14, testData)
        String note = createNote(username, now, notes)
        s.setBytes(15, StringUtilities.getBytes(note, "UTF-8"))
        s.setInt(16, changed ? 1 : 0)

        NCubeInfoDto dto = new NCubeInfoDto()
        dto.id = Long.toString(uniqueId)
        dto.name = name
        dto.sha1 = sha1
        dto.headSha1 = sha1
        dto.changed = changed
        dto.tenant = appId.tenant
        dto.app = appId.app
        dto.version = appId.version
        dto.status = appId.status
        dto.branch = appId.branch
        dto.createDate = new Date(time)
        dto.createHid = username
        dto.notes = note
        dto.revision = Long.toString(revision)

        return s.executeUpdate() == 1 ? dto : null
    }

    NCubeInfoDto insertCube(Connection c, ApplicationID appId, NCube cube, Long revision, byte[] testData, String notes, boolean changed, String headSha1, long time, String username)
    {
        long uniqueId = UniqueIdGenerator.getUniqueId()
        Timestamp now = new Timestamp(time)
        final Blob blob = c.createBlob()
        OutputStream out = blob.setBinaryStream(1L)
        OutputStream stream = new GZIPOutputStream(out, 8192)
        new JsonFormatter(stream).formatCube(cube)

        PreparedStatement s = c.prepareStatement("""\
INSERT INTO n_cube (n_cube_id, tenant_cd, app_cd, version_no_cd, status_cd, branch_id, n_cube_nm, revision_number,
 sha1, head_sha1, create_dt, create_hid, cube_value_bin, test_data_bin, notes_bin, changed)
 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        s.setLong(1, uniqueId)
        s.setString(2, appId.tenant)
        s.setString(3, appId.app)
        s.setString(4, appId.version)
        s.setString(5, appId.status)
        s.setString(6, appId.branch)
        s.setString(7, cube.name)
        s.setLong(8, revision)
        s.setString(9, cube.sha1())
        s.setString(10, headSha1)
        s.setTimestamp(11, now)
        s.setString(12, username)
        s.setBlob(13, blob)
        s.setBytes(14, testData)
        String note = createNote(username, now, notes)
        s.setBytes(15, StringUtilities.getBytes(note, "UTF-8"))
        s.setBoolean(16, changed)

        NCubeInfoDto dto = new NCubeInfoDto()
        dto.id = Long.toString(uniqueId)
        dto.name = cube.name
        dto.sha1 = cube.sha1()
        dto.headSha1 = cube.sha1()
        dto.changed = changed
        dto.tenant = appId.tenant
        dto.app = appId.app
        dto.version = appId.version
        dto.status = appId.status
        dto.branch = appId.branch
        dto.createDate = new Date(time)
        dto.createHid = username
        dto.notes = note
        dto.revision = Long.toString(revision)

        return s.executeUpdate() == 1 ? dto : null
    }

    boolean deleteCube(Connection c, ApplicationID appId, String cubeName, boolean allowDelete, String username)
    {
        if (allowDelete)
        {
            String sqlCmd = "DELETE FROM n_cube WHERE app_cd = ? AND " + buildNameCondition(c, "n_cube_nm") + " = ? AND version_no_cd = ? AND tenant_cd = RPAD(?, 10, ' ') AND branch_id = ?"

            PreparedStatement ps = c.prepareStatement(sqlCmd)
            ps.setString(1, appId.app)
            ps.setString(2, buildName(c, cubeName))
            ps.setString(3, appId.version)
            ps.setString(4, appId.tenant)
            ps.setString(5, appId.branch)
            return ps.executeUpdate() > 0
        }
        else
        {
            Map<String, Object> options = [(NCubeManager.SEARCH_ACTIVE_RECORDS_ONLY): true,
                                           (NCubeManager.SEARCH_INCLUDE_CUBE_DATA): true,
                                           (NCubeManager.SEARCH_INCLUDE_TEST_DATA): true,
                                           (NCubeManager.SEARCH_EXACT_MATCH_NAME): true] as Map

            boolean result = false
            runSelectCubesStatement(c, appId, cubeName, options, { ResultSet row ->
                if (result)
                {
                    throw new IllegalStateException('Error deleting single cube, more than one matched, cube: ' + cubeName + ', app: ' + appId)
                }
                Long revision = row.getLong('revision_number')
                byte[] jsonBytes = row.getBytes(CUBE_VALUE_BIN)
                byte[] testData = row.getBytes(TEST_DATA_BIN)
                String sha1 = row.getString('sha1')
                String headSha1 = row.getString('head_sha1')

                if (insertCube(c, appId, cubeName, -(revision + 1), jsonBytes, testData, "Cube deleted", true, sha1, headSha1, System.currentTimeMillis(), username) == null)
                {
                    throw new IllegalStateException("Cannot delete n-cube: " + cubeName + ", app: " + appId + ", row was not deleted")
                }
                result = true
            })
            return result
        }
    }

    void restoreCube(Connection c, ApplicationID appId, String cubeName, String username)
    {
        Map<String, Object> options = [(NCubeManager.SEARCH_DELETED_RECORDS_ONLY): true,
                                       (NCubeManager.SEARCH_INCLUDE_CUBE_DATA): true,
                                       (NCubeManager.SEARCH_INCLUDE_TEST_DATA): true,
                                       (NCubeManager.SEARCH_EXACT_MATCH_NAME): true] as Map

        Long revision = null
        runSelectCubesStatement(c, appId, cubeName, options, { ResultSet row ->
            if (revision != null)
            {
                throw new IllegalStateException('Only 1 cube should have matched')
            }
            revision = row.getLong('revision_number')
            byte[] jsonBytes = row.getBytes(CUBE_VALUE_BIN)
            byte[] testData = row.getBytes(TEST_DATA_BIN)
            String notes = "Cube restored"
            String sha1 = row.getString('sha1')
            String headSha1 = row.getString('head_sha1')

            NCubeInfoDto dto = insertCube(c, appId, cubeName, Math.abs(revision as long) + 1, jsonBytes, testData, notes, true, sha1, headSha1, System.currentTimeMillis(), username)
            if (dto == null)
            {
                throw new IllegalStateException("Could not add restored cube: " + cubeName + ", app: " + appId)
            }
        })

        if (revision == null)
        {
            throw new IllegalArgumentException("Cannot restore cube: " + cubeName + " as it not deleted in app: " + appId)
        }
    }

    NCubeInfoDto updateCube(Connection c, ApplicationID appId, Long cubeId, String username)
    {
        if (cubeId == null)
        {
            throw new IllegalArgumentException("Update cube, cube id cannot be empty, app: " + appId)
        }

        // select head cube in question
        String sql = "SELECT n_cube_nm, app_cd, version_no_cd, status_cd, revision_number, branch_id, cube_value_bin, test_data_bin, notes_bin, sha1, head_sha1, create_dt from n_cube WHERE n_cube_id = ?"
        PreparedStatement stmt = c.prepareStatement(sql)
        stmt.setLong(1, cubeId)
        ResultSet row = stmt.executeQuery()

        if (row.next())
        {
            byte[] jsonBytes = row.getBytes(CUBE_VALUE_BIN)
            String sha1 = row.getString("sha1")
            String cubeName = row.getString("n_cube_nm")
            Long revision = row.getLong("revision_number")
            long time = row.getTimestamp("create_dt").getTime()
            byte[] testData = row.getBytes(TEST_DATA_BIN)

            Long maxRevision = getMaxRevision(c, appId, cubeName)

            //  create case because max revision was not found.
            if (maxRevision == null)
            {
                maxRevision = revision < 0 ? new Long(-1) : new Long(0)
            }
            else if (revision < 0)
            {
                // cube deleted in branch
                maxRevision = -(Math.abs(maxRevision as long) + 1)
            }
            else
            {
                maxRevision = Math.abs(maxRevision as long) + 1
            }

            NCubeInfoDto dto = insertCube(c, appId, cubeName, maxRevision, jsonBytes, testData, "Cube updated from HEAD", false, sha1, sha1, time, username)

            if (dto == null)
            {
                String s = "Unable to update cube: " + cubeName + " to app:  " + appId
                throw new IllegalStateException(s)
            }
            return dto
        }
        return null
    }

    void updateCube(Connection c, ApplicationID appId, NCube cube, String username)
    {
        Map<String, Object> options = [(NCubeManager.SEARCH_INCLUDE_CUBE_DATA): true,
                                       (NCubeManager.SEARCH_INCLUDE_TEST_DATA): true,
                                       (NCubeManager.SEARCH_EXACT_MATCH_NAME): true] as Map

        boolean rowFound = false
        runSelectCubesStatement(c, appId, cube.name, options, { ResultSet row ->
            if (rowFound)
            {
                throw new IllegalStateException('2nd row found attempting to update cube: ' + cube.name + ', app: ' + appId)
            }
            rowFound = true
            Long revision = row.getLong("revision_number")
            byte[] testData = row.getBytes(TEST_DATA_BIN)

            if (revision < 0)
            {
                testData = null
            }

            String headSha1 = row.getString('head_sha1')
            String oldSha1 = row.getString('sha1')

            if (StringUtilities.equals(oldSha1, cube.sha1()) && revision >= 0)
            {
                //  shas are equals and both revision values are positive.  no need for new revision of record.
                return
            }

            NCubeInfoDto dto = insertCube(c, appId, cube, Math.abs(revision as long) + 1, testData, "Cube updated", true, headSha1, System.currentTimeMillis(), username)

            if (dto == null)
            {
                throw new IllegalStateException("error updating n-cube: " + cube.name + ", app: " + appId + ", row was not updated")
            }
        })

        // No existing row found, then create a new cube (updateCube can be used for update or create)
        if (!rowFound)
        {
            if (insertCube(c, appId, cube, 0L, null, "Cube created", true, null, System.currentTimeMillis(), username) == null)
            {
                throw new IllegalStateException("error inserting new n-cube: " + cube.name + ", app: " + appId)
            }
        }
    }

    boolean duplicateCube(Connection c, ApplicationID oldAppId, ApplicationID newAppId, String oldName, String newName, String username)
    {
        byte[] jsonBytes = null
        Long oldRevision = null
        byte[] oldTestData = null
        String sha1 = null

        Map<String, Object> options = [
                (NCubeManager.SEARCH_INCLUDE_CUBE_DATA):true,
                (NCubeManager.SEARCH_INCLUDE_TEST_DATA):true,
                (NCubeManager.SEARCH_EXACT_MATCH_NAME):true] as Map

        runSelectCubesStatement(c, oldAppId, oldName, options, { ResultSet row ->
            if (oldRevision != null)
            {
                throw new IllegalStateException('Error, duplicating cube, only 1 source record should be found, name: ' + oldName + ', app: ' + oldAppId)
            }
            jsonBytes = row.getBytes(CUBE_VALUE_BIN)
            oldRevision = row.getLong('revision_number')
            oldTestData = row.getBytes(TEST_DATA_BIN)
            sha1 = row.getString('sha1')
        })

        if (oldRevision == null)
        {   // not found
            throw new IllegalArgumentException("Could not duplicate cube because cube does not exist, app:  " + oldAppId + ", name: " + oldName)
        }

        if (oldRevision < 0)
        {
            throw new IllegalArgumentException("Unable to duplicate deleted cube, app:  " + oldAppId + ", name: " + oldName)
        }

        Long newRevision = null
        String headSha1 = null

        runSelectCubesStatement(c, newAppId, newName, options, { ResultSet row ->
            newRevision = row.getLong('revision_number')
            headSha1 = row.getString('head_sha1')
        })

        if (newRevision != null && newRevision >= 0)
        {
            throw new IllegalArgumentException("Unable to duplicate cube, cube already exists with the new name, app:  " + newAppId + ", name: " + newName)
        }

        boolean changed = !StringUtilities.equalsIgnoreCase(oldName, newName)
        boolean sameExceptBranch = oldAppId.equalsNotIncludingBranch(newAppId)

        // If names are different we need to recalculate the sha-1
        if (changed)
        {
            NCube ncube = NCube.createCubeFromGzipBytes(jsonBytes)
            ncube.setName(newName)
            ncube.setApplicationID(newAppId)
            jsonBytes = ncube.getCubeAsGzipJsonBytes()
            sha1 = ncube.sha1()
        }

        String notes = 'Cube duplicated from app: ' + oldAppId + ', name: ' + oldName
        Long rev = newRevision == null ? 0L : Math.abs(newRevision as long) + 1L

        if (insertCube(c, newAppId, newName, rev, jsonBytes, oldTestData, notes, changed, sha1, sameExceptBranch ? headSha1 : null, System.currentTimeMillis(), username) == null)
        {
            throw new IllegalStateException("Unable to duplicate cube: " + oldName + ", app: " + oldAppId + " to cube: " + newName + ", app: " + newAppId)
        }
        return true
    }

    boolean renameCube(Connection c, ApplicationID appId, String oldName, String newName, String username)
    {
        byte[] oldBytes = null
        Long oldRevision = null
        String oldSha1 = null
        String oldHeadSha1 = null
        byte[] oldTestData = null

        Map<String, Object> options = [
                (NCubeManager.SEARCH_INCLUDE_CUBE_DATA):true,
                (NCubeManager.SEARCH_INCLUDE_TEST_DATA):true,
                (NCubeManager.SEARCH_EXACT_MATCH_NAME):true] as Map

        runSelectCubesStatement(c, appId, oldName, options, { ResultSet row ->
            if (oldRevision != null)
            {
                throw new IllegalStateException('Error, rename cube, more than 1 record found, cube: ' + oldName + ', app: ' + appId)
            }
            oldBytes = row.getBytes(CUBE_VALUE_BIN)
            oldRevision = row.getLong('revision_number')
            oldTestData = row.getBytes(TEST_DATA_BIN)
            oldSha1 = row.getString('sha1')
            oldHeadSha1 = row.getString('head_sha1')
        })

        if (oldRevision == null)
        {   // not found
            throw new IllegalArgumentException("Could not rename cube because cube does not exist, app:  " + appId + ", name: " + oldName)
        }

        if (oldRevision != null && oldRevision < 0)
        {
            throw new IllegalArgumentException("Deleted cubes cannot be renamed.  AppId:  " + appId + ", " + oldName + " -> " + newName)
        }

        Long newRevision = null
        String newHeadSha1 = null

        runSelectCubesStatement(c, appId, newName, options, { ResultSet row ->
            newRevision = row.getLong('revision_number')
            newHeadSha1 = row.getString(HEAD_SHA_1)
        })

        if (newRevision != null && newRevision >= 0)
        {
            throw new IllegalArgumentException("Unable to rename cube, a cube already exists with that name, app:  " + appId + ", name: " + newName)
        }

        NCube ncube = NCube.createCubeFromGzipBytes(oldBytes)
        ncube.setName(newName)
        String notes = "Cube renamed: " + oldName + " -> " + newName

        Long rev = newRevision == null ? 0L : Math.abs(newRevision as long) + 1L
        if (insertCube(c, appId, ncube, rev, oldTestData, notes, true, newHeadSha1, System.currentTimeMillis(), username) == null)
        {
            throw new IllegalStateException("Unable to rename cube: " + oldName + " -> " + newName + ", app: " + appId)
        }

        if (insertCube(c, appId, oldName, -(oldRevision + 1), oldBytes, oldTestData, notes, true, oldSha1, oldHeadSha1, System.currentTimeMillis(), username) == null)
        {
            throw new IllegalStateException("Unable to rename cube: " + oldName + " -> " + newName + ", app: " + appId)
        }
        return true
    }

    NCubeInfoDto commitMergedCubeToBranch(Connection c, ApplicationID appId, NCube cube, String headSha1, String username)
    {
        Map options = [(NCubeManager.SEARCH_INCLUDE_TEST_DATA):true,
                       (NCubeManager.SEARCH_EXACT_MATCH_NAME):true] as Map

        NCubeInfoDto result = null

        runSelectCubesStatement(c, appId, cube.name, options, { ResultSet row ->
            if (result != null)
            {
                throw new IllegalStateException('Should only match one record, (commit merged cube to branch), app: ' + appId + ", cube: " + cube.name)
            }
            Long revision = row.getLong('revision_number')
            byte[] testData = row.getBytes(TEST_DATA_BIN)
            long now = System.currentTimeMillis()
            revision = revision < 0 ? revision - 1 : revision + 1
            result = insertCube(c, appId, cube, revision, testData, "Cube committed", true, headSha1, now, username)
        })
        return result
    }

    NCubeInfoDto commitMergedCubeToHead(Connection c, ApplicationID appId, NCube cube, String username)
    {
        Map options = [(NCubeManager.SEARCH_INCLUDE_TEST_DATA):true,
                       (NCubeManager.SEARCH_EXACT_MATCH_NAME):true]

        ApplicationID headAppId = appId.asHead()
        NCubeInfoDto result = null

        runSelectCubesStatement(c, appId, cube.name, options, { ResultSet row ->
            if (result != null)
            {
                throw new IllegalStateException('Should only match one record, (commit merged cube to HEAD), app: ' + appId + ", cube: " + cube.name)
            }
            Long revision = row.getLong('revision_number')

            // get current max HEAD revision
            Long maxRevision = getMaxRevision(c, headAppId, cube.name)

            if (maxRevision == null)
            {
                maxRevision = revision < 0 ? -1L : 0L
            }
            else if (revision < 0)
            {
                // cube deleted in branch
                maxRevision = -(Math.abs(maxRevision as long) + 1)
            }
            else
            {
                maxRevision = Math.abs(maxRevision as long) + 1
            }

            byte[] testData = row.getBytes(TEST_DATA_BIN)
            long now = System.currentTimeMillis()
            // ok to use this here, because we're writing out these bytes twice (once to head and once to branch)
            byte[] cubeData = cube.getCubeAsGzipJsonBytes()
            String sha1 = cube.sha1()

            NCubeInfoDto head = insertCube(c, headAppId, cube.name, maxRevision, cubeData, testData, "Cube committed", false, sha1, null, now, username)

            if (head == null)
            {
                String s = "Unable to commit cube: " + cube.name + " to app:  " + appId
                throw new IllegalStateException(s)
            }

            result = insertCube(c, appId, cube.name, revision > 0 ? ++revision : --revision, cubeData, testData, "Cube committed", false, sha1, sha1, now, username)
        })
        return result
    }

    NCubeInfoDto commitCube(Connection c, ApplicationID appId, Long cubeId, String username)
    {
        if (cubeId == null)
        {
            throw new IllegalArgumentException("Commit cube, cube id cannot be empty, app: " + appId)
        }

        ApplicationID headAppId = appId.asHead()
        def map = [id:cubeId]
        Sql sql = new Sql(c)
        NCubeInfoDto result = null

        sql.eachRow("SELECT n_cube_nm, app_cd, version_no_cd, status_cd, revision_number, branch_id, cube_value_bin, test_data_bin, notes_bin, sha1, head_sha1 from n_cube WHERE n_cube_id = :id",
                map, { ResultSet row ->
            if (result != null)
            {
                throw new IllegalStateException('Should only match one record, (commit), app: ' + appId + ', cube id: ' + cubeId)
            }
            byte[] jsonBytes = row.getBytes(CUBE_VALUE_BIN)
            String sha1 = row.getString("sha1")
            String cubeName = row.getString("n_cube_nm")
            Long revision = row.getLong("revision_number")
            Long maxRevision = getMaxRevision(c, headAppId, cubeName)

            //  create case because max revision was not found.
            if (maxRevision == null)
            {
                maxRevision = revision < 0 ? new Long(-1) : new Long(0)
            }
            else if (revision < 0)
            {
                // cube deleted in branch
                maxRevision = -(Math.abs(maxRevision as long) + 1)
            }
            else
            {
                maxRevision = Math.abs(maxRevision as long) + 1
            }

            byte[] testData = row.getBytes(TEST_DATA_BIN)

            long now = System.currentTimeMillis()

            NCubeInfoDto dto = insertCube(c, headAppId, cubeName, maxRevision, jsonBytes, testData, "Cube committed", false, sha1, null, now, username)

            if (dto == null)
            {
                String s = "Unable to commit cube: " + cubeName + " to app:  " + headAppId
                throw new IllegalStateException(s)
            }

            def map1 = [head_sha1: sha1, create_dt: new Timestamp(now), id: cubeId]
            Sql sql1 = new Sql(c)
            sql1.executeUpdate(map1, 'UPDATE n_cube set head_sha1 = :head_sha1, changed = 0, create_dt = :create_dt WHERE n_cube_id = :id')

            dto.changed = false
            dto.id = Long.toString(cubeId)
            dto.sha1 = sha1
            dto.headSha1 = sha1
            result = dto
        })

        return result
    }

    /**
     * Rollback branch cube to initial state when it was created from the HEAD.  This is different
     * than what the HEAD cube is currently.  This is also different than deleting the branch cube's
     * history.  We are essentially copying the initial revision to the end (max revision).  This
     * approach maintains revision history in the branch (and adheres to the no SQL DELETE rule on
     * n_cube table).
     */
    boolean rollbackCube(Connection c, ApplicationID appId, String cubeName, String username)
    {
        Long revision = getMinRevision(c, appId, cubeName)

        if (revision == null)
        {
            throw new IllegalArgumentException("Could not rollback cube.  Cube was not found.  App:  " + appId + ", cube: " + cubeName)
        }

        Map map = appId as Map
        map.putAll([cube: buildName(c, cubeName), rev: revision])
        Sql sql = new Sql(c)
        boolean result = false
        sql.eachRow(map, """\
SELECT n_cube_id, n_cube_nm, app_cd, version_no_cd, status_cd, revision_number, branch_id, cube_value_bin, test_data_bin, notes_bin, changed, sha1, head_sha1, create_dt
 FROM n_cube
 WHERE """ + buildNameCondition(c, "n_cube_nm") + """ = :cube AND app_cd = :app AND version_no_cd = :version AND status_cd = :status
 AND tenant_cd = RPAD(:tenant, 10, ' ') AND branch_id = :branch AND revision_number = :rev""", 0, 1, { ResultSet row ->
            byte[] bytes = row.getBytes(CUBE_VALUE_BIN)
            byte[] testData = row.getBytes(TEST_DATA_BIN)
            String sha1 = row.getString('sha1')
            String headSha1 = row.getString('head_sha1')

            Long newRevision = getMaxRevision(c, appId, cubeName)
            if (newRevision == null)
            {
                throw new IllegalStateException("failed to rollback because branch cube does not exist: " + cubeName + ", app: " + appId)
            }

            String notes = "Rollback of cube: " + cubeName + ", app: " + appId + ", was successful"
            Long rev = Math.abs(newRevision as long) + 1L

            if (insertCube(c, appId, cubeName, (revision < 0 || headSha1 == null) ?  -rev : rev, bytes, testData, notes, false, sha1, headSha1, System.currentTimeMillis(), username) == null)
            {
                throw new IllegalStateException("Unable to rollback branch cube: '" + cubeName + ", app: " + appId)
            }
            result = true
        })
        return result
    }

    boolean updateBranchCubeHeadSha1(Connection c, Long cubeId, String headSha1)
    {
        if (cubeId == null)
        {
            throw new IllegalArgumentException("Update branch cube's HEAD SHA-1, cube id cannot be empty")
        }

        if (StringUtilities.isEmpty(headSha1))
        {
            throw new IllegalArgumentException("Update branch cube's HEAD SHA-1, SHA-1 cannot be empty")
        }

        Map map = [sha1:headSha1, id: cubeId]
        Sql sql = new Sql(c)
        int count = sql.executeUpdate(map, 'UPDATE n_cube set head_sha1 = :sha1, changed = 0 WHERE n_cube_id = :id')
        if (count == 0)
        {
            throw new IllegalStateException("error updating branch cube: " + cubeId + ", to HEAD SHA-1: " + headSha1 + ", no record found.")
        }
        if (count != 1)
        {
            throw new IllegalStateException("error updating branch cube: " + cubeId + ", to HEAD SHA-1: " + headSha1 + ", more than one record found: " + count)
        }
        return true
    }

    boolean mergeAcceptMine(Connection c, ApplicationID appId, String cubeName, String username)
    {
        ApplicationID headId = appId.asHead()
        Long headRevision = null
        String headSha1 = null

        Map<String, Object> options = [(NCubeManager.SEARCH_EXACT_MATCH_NAME): true] as Map

        runSelectCubesStatement(c, headId, cubeName, options, { ResultSet row ->
            if (headRevision != null)
            {
                throw new IllegalStateException("Error, on 'merge accept mine': only 1 HEAD record should be returned: " + cubeName + ", app: " + appId)
            }
            headRevision = row.getLong('revision_number')
            headSha1 = row.getString('sha1')
        })

        if (headRevision == null)
        {
            throw new IllegalStateException("failed to update branch cube because HEAD cube does not exist: " + cubeName + ", app: " + appId)
        }

        Long newRevision = null
        String tipBranchSha1 = null
        byte[] myTestData = null
        byte[] myBytes = null
        boolean changed = false
        options.put(NCubeManager.SEARCH_INCLUDE_CUBE_DATA, true)
        options.put(NCubeManager.SEARCH_INCLUDE_TEST_DATA, true)

        runSelectCubesStatement(c, appId, cubeName, options, { ResultSet row ->
            if (newRevision != null)
            {
                throw new IllegalStateException("Error, on 'merge accept mine': only 1 branch record should be returned: " + cubeName + ", app: " + appId)
            }
            myBytes = row.getBytes(CUBE_VALUE_BIN)
            myTestData = row.getBytes(TEST_DATA_BIN)
            newRevision = row.getLong('revision_number')
            tipBranchSha1 = row.getString('sha1')
            changed = row.getBoolean('changed')
        })

        if (newRevision == null)
        {
            throw new IllegalStateException("failed to update branch cube because branch cube does not exist: " + cubeName + ", app: " + appId)
        }

        String notes = "Merge: branch cube accepted over head cube: " + appId + ", name: " + cubeName
        Long rev = Math.abs(newRevision as long) + 1L

        if (insertCube(c, appId, cubeName, newRevision < 0 ?  -rev : rev, myBytes, myTestData, notes, changed, tipBranchSha1, headSha1, System.currentTimeMillis(), username) == null)
        {
            throw new IllegalStateException("Unable to overwrite branch cube: " + cubeName + ", app: " + appId)
        }

        return true
    }

    boolean mergeAcceptTheirs(Connection c, ApplicationID appId, String cubeName, String branchSha1, String username)
    {
        ApplicationID headId = appId.asHead()
        byte[] headBytes = null
        Long headRevision = null
        byte[] headTestData = null
        String headSha1 = null

        Map<String, Object> options = [
                (NCubeManager.SEARCH_INCLUDE_CUBE_DATA):true,
                (NCubeManager.SEARCH_INCLUDE_TEST_DATA):true,
                (NCubeManager.SEARCH_EXACT_MATCH_NAME):true] as Map

        runSelectCubesStatement(c, headId, cubeName, options, { ResultSet row ->
            if (headRevision != null)
            {
                throw new IllegalStateException("Error, more than 1 record came back from 'their' branch, cube: " + cubeName + ", app: " + appId)
            }
            headBytes = row.getBytes(CUBE_VALUE_BIN)
            headTestData = row.getBytes(TEST_DATA_BIN)
            headRevision = row.getLong('revision_number')
            headSha1 = row.getString('sha1')
        })

        if (headRevision == null)
        {
            throw new IllegalStateException("Failed to overwrite cube in your branch, because 'their' cube does not exist: " + cubeName + ", app: " + appId)
        }

        Long newRevision = null
        String oldBranchSha1 = null

        runSelectCubesStatement(c, appId, cubeName, options, { ResultSet row ->
            newRevision = row.getLong('revision_number')
            oldBranchSha1 = row.getString('sha1')
        })

        if (newRevision == null)
        {
            throw new IllegalStateException("failed to overwrite cube in your branch, because branch cube does not exist: " + cubeName + ", app: " + appId)
        }

        if (!StringUtilities.equalsIgnoreCase(branchSha1, oldBranchSha1))
        {
            throw new IllegalStateException("failed to overwrite cube in your branch, because branch cube has changed: " + cubeName + ", app: " + appId)
        }

        String notes = "Branch cube overwritten: " + appId + ", name: " + cubeName
        Long rev = Math.abs(newRevision as long) + 1L

        if (insertCube(c, appId, cubeName, headRevision < 0 ?  -rev : rev, headBytes, headTestData, notes, false, headSha1, headSha1, System.currentTimeMillis(), username) == null)
        {
            throw new IllegalStateException("Unable to overwrite branch cube: " + cubeName + ", app: " + appId)
        }
        return true
    }

    /**
     * @param c Connection (JDBC) from ConnectionProvider
     * @param appId ApplicationID
     * @param namePattern String name pattern (using wildcards * and ?)
     * @param options map with possible keys:
     *                changedRecordsOnly - default false
     *                activeRecordsOnly - default false
     *                deletedRecordsOnly - default false
     *                includeCubeData - default false
     *                includeTestData - default false
     *                exactMatchName - default false
     * @param closure Closure to run for each record selected.
     */
    protected void runSelectCubesStatement(Connection c, ApplicationID appId, String namePattern, Map options, Closure closure)
    {
        boolean changedRecordsOnly = toBoolean(options[NCubeManager.SEARCH_CHANGED_RECORDS_ONLY])
        boolean activeRecordsOnly = toBoolean(options[NCubeManager.SEARCH_ACTIVE_RECORDS_ONLY])
        boolean deletedRecordsOnly = toBoolean(options[NCubeManager.SEARCH_DELETED_RECORDS_ONLY])
        boolean includeCubeData = toBoolean(options[NCubeManager.SEARCH_INCLUDE_CUBE_DATA])
        boolean includeTestData = toBoolean(options[NCubeManager.SEARCH_INCLUDE_TEST_DATA])
        boolean exactMatchName = toBoolean(options[NCubeManager.SEARCH_EXACT_MATCH_NAME])

        if (activeRecordsOnly && deletedRecordsOnly)
        {
            throw new IllegalArgumentException("activeRecordsOnly and deletedRecordsOnly are mutually exclusive options and cannot both be 'true'.")
        }

        //  convert pattern will return null even if full pattern = '*', saying we don't need to do the like statement.
        //  That is why it is before the hasNamePattern.

        namePattern = convertPattern(buildName(c, namePattern))
        boolean hasNamePattern = StringUtilities.hasContent(namePattern)

        String nameCondition = ''

        Map map = appId as Map
        map.putAll([name:namePattern, changed:changedRecordsOnly])

        if (hasNamePattern)
        {
            nameCondition = ' AND ' + buildNameCondition(c, 'a.n_cube_nm') + (exactMatchName ? ' = :name' : ' LIKE :name')
        }

        String revisionCondition = activeRecordsOnly ? ' AND a.revision_number >= 0' : deletedRecordsOnly ? ' AND a.revision_number < 0' : ''
        String changedCondition = changedRecordsOnly ? ' AND a.changed = :changed' : ''
        String testCondition = includeTestData ? ', a.test_data_bin' : ''
        String cubeCondition = includeCubeData ? ', a.cube_value_bin' : ''

        Sql sql = new Sql(c)

        sql.eachRow(map, """\
SELECT a.n_cube_id, a.n_cube_nm, a.app_cd, a.notes_bin, a.version_no_cd, a.status_cd, a.create_dt, a.create_hid,
       a.revision_number, a.branch_id, a.changed, a.sha1, a.head_sha1""" + testCondition + cubeCondition + """
 FROM n_cube a
 LEFT OUTER JOIN n_cube b
 ON abs(a.revision_number) < abs(b.revision_number) AND """ + nameCompareCondition(c) + """ AND a.app_cd = b.app_cd
 AND a.status_cd = b.status_cd AND a.version_no_cd = b.version_no_cd AND a.tenant_cd = b.tenant_cd AND a.branch_id = b.branch_id
 WHERE a.app_cd = :app AND a.status_cd = :status AND a.version_no_cd = :version AND a.tenant_cd = RPAD(:tenant, 10, ' ')
 AND a.branch_id = :branch AND b.n_cube_nm is NULL""" +
                nameCondition +
                revisionCondition +
                changedCondition, closure)
    }

    int createBranch(Connection c, ApplicationID appId)
    {
        if (doCubesExist(c, appId, true))
        {
            throw new IllegalStateException("Branch '" + appId.branch + "' already exists, app: " + appId)
        }

        ApplicationID headId = appId.asHead()
        Map<String, Object> options = [(NCubeManager.SEARCH_INCLUDE_CUBE_DATA): true,
                                       (NCubeManager.SEARCH_INCLUDE_TEST_DATA): true] as Map

        int count = 0
        PreparedStatement insert = c.prepareStatement(
                "INSERT INTO n_cube (n_cube_id, n_cube_nm, cube_value_bin, create_dt, create_hid, version_no_cd, status_cd, app_cd, test_data_bin, notes_bin, tenant_cd, branch_id, revision_number, changed, sha1, head_sha1) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")

        runSelectCubesStatement(c, headId, null, options, { ResultSet row ->
            String sha1 = row['sha1'] as String

            insert.setLong(1, UniqueIdGenerator.getUniqueId())
            insert.setString(2, row.getString('n_cube_nm'))
            insert.setBytes(3, row.getBytes(CUBE_VALUE_BIN))
            insert.setTimestamp(4, row.getTimestamp('create_dt'))
            insert.setString(5, row.getString('create_hid'))
            insert.setString(6, appId.version)
            insert.setString(7, ReleaseStatus.SNAPSHOT.name())
            insert.setString(8, appId.app)
            insert.setBytes(9, row.getBytes(TEST_DATA_BIN))
            insert.setBytes(10, row.getBytes(NOTES_BIN))
            insert.setString(11, appId.tenant)
            insert.setString(12, appId.branch)
            insert.setLong(13, (row.getLong('revision_number') >= 0) ? 0 : -1)
            insert.setBoolean(14, false)
            insert.setString(15, sha1)
            insert.setString(16, sha1)
            insert.addBatch()
            count++
            if (count % EXECUTE_BATCH_CONSTANT == 0)
            {
                insert.executeBatch()
            }
        })
        if (count % EXECUTE_BATCH_CONSTANT != 0)
        {
            insert.executeBatch()
        }

        return count
    }

    boolean deleteBranch(Connection c, ApplicationID appId)
    {
        Map map = appId as Map
        new Sql(c).execute(map, "DELETE FROM n_cube WHERE app_cd = :app AND version_no_cd = :version AND tenant_cd = RPAD(:tenant, 10, ' ') AND branch_id = :branch")
        return true
    }

    int releaseCubes(Connection c, ApplicationID appId, String newSnapVer)
    {
        if (doReleaseCubesExist(c, appId))
        {
            throw new IllegalStateException("A RELEASE version " + appId.version + " already exists, app: " + appId)
        }

        // Step 1: Move everyone's SNAPSHOT version cubes to new version.
        // (Update version number to new version where branch != HEAD (and rest of appId matches) ignore revision)
        Map map = appId as Map
        map.putAll([newVer: newSnapVer])
        Sql sql = new Sql(c)
        sql.executeUpdate(map, "UPDATE n_cube SET version_no_cd = :newVer WHERE app_cd = :app AND version_no_cd = :version AND tenant_cd = RPAD(:tenant, 10, ' ') AND branch_id != 'HEAD'")

        // Step 2: Release cubes where branch == HEAD (change their status from SNAPSHOT to RELEASE)
        map.create_dt = new Timestamp(System.currentTimeMillis())
        int releaseCount = sql.executeUpdate(map, "UPDATE n_cube SET create_dt = :create_dt, status_cd = 'RELEASE' WHERE app_cd = :app AND version_no_cd = :version AND status_cd = 'SNAPSHOT' AND tenant_cd = RPAD(:tenant, 10, ' ') AND branch_id = 'HEAD'")

        // Step 3: Create new SNAPSHOT cubes from the HEAD RELEASE cubes (next version higher, started for development)
        ApplicationID releaseId = appId.asRelease()

        Map<String, Object> options = [(NCubeManager.SEARCH_ACTIVE_RECORDS_ONLY): true,
                                   (NCubeManager.SEARCH_INCLUDE_TEST_DATA): true,
                                   (NCubeManager.SEARCH_INCLUDE_CUBE_DATA): true] as Map

        boolean autoCommit = c.getAutoCommit()
        c.setAutoCommit(false)
        int count = 0
        PreparedStatement insert = c.prepareStatement(
                "INSERT INTO n_cube (n_cube_id, n_cube_nm, cube_value_bin, create_dt, create_hid, version_no_cd, status_cd, app_cd, test_data_bin, notes_bin, tenant_cd, branch_id, revision_number) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")

        runSelectCubesStatement(c, releaseId, null, options, { ResultSet row ->
            insert.setLong(1, UniqueIdGenerator.getUniqueId())
            insert.setString(2, row.getString('n_cube_nm'))
            insert.setBytes(3, row.getBytes(CUBE_VALUE_BIN))
            insert.setTimestamp(4, new Timestamp(System.currentTimeMillis()))
            insert.setString(5, row.getString('create_hid'))
            insert.setString(6, newSnapVer)
            insert.setString(7, ReleaseStatus.SNAPSHOT.name())
            insert.setString(8, appId.app)
            insert.setBytes(9, row.getBytes(TEST_DATA_BIN))
            insert.setBytes(10, row.getBytes(NOTES_BIN))
            insert.setString(11, appId.tenant)
            insert.setString(12, ApplicationID.HEAD)
            insert.setLong(13, 0) // New SNAPSHOT revision numbers start at 0, we don't move forward deleted records.
            insert.addBatch()
            count++
            if (count % EXECUTE_BATCH_CONSTANT == 0)
            {
                insert.executeBatch()
            }
        })

        if (count % EXECUTE_BATCH_CONSTANT != 0)
        {
            insert.executeBatch()
            c.commit()
        }
        c.setAutoCommit(autoCommit)
        return releaseCount
    }

    int changeVersionValue(Connection c, ApplicationID appId, String newVersion)
    {
        ApplicationID newSnapshot = appId.createNewSnapshotId(newVersion)
        if (doCubesExist(c, newSnapshot, true))
        {
            throw new IllegalStateException("Cannot change version value to " + newVersion + " because cubes with this version already exists.  Choose a different version number, app: " + appId)
        }

        Map map = appId as Map
        map.putAll([newVer: newVersion, status: 'SNAPSHOT'])
        Sql sql = new Sql(c)
        int count = sql.executeUpdate(map, "UPDATE n_cube SET version_no_cd = :newVer WHERE app_cd = :app AND version_no_cd = :version AND status_cd = :status AND tenant_cd = RPAD(:tenant, 10, ' ') AND branch_id = :branch")
        if (count < 1)
        {
            throw new IllegalArgumentException("No SNAPSHOT n-cubes found with version " + appId.version + ", therefore no versions updated, app: " + appId)
        }
        return count
    }

    boolean updateTestData(Connection c, ApplicationID appId, String cubeName, String testData)
    {
        Map map = appId as Map
        map.putAll([testData: testData == null ? null : testData.getBytes("UTF-8"),
                    status: ReleaseStatus.SNAPSHOT.name(), cube: buildName(c, cubeName)])
        Sql sql = new Sql(c)

        int rows = sql.executeUpdate(map, """\
UPDATE n_cube SET test_data_bin = :testData
WHERE n_cube_id = (SELECT a.n_cube_id
 FROM n_cube a
 LEFT OUTER JOIN n_cube b
 ON abs(a.revision_number) < abs(b.revision_number) AND """ + nameCompareCondition(c) + """ AND a.app_cd = b.app_cd
 AND a.status_cd = b.status_cd AND a.version_no_cd = b.version_no_cd AND a.tenant_cd = b.tenant_cd AND a.branch_id = b.branch_id
 WHERE """ + buildNameCondition(c, "a.n_cube_nm") + """ = :cube AND a.app_cd = :app AND a.status_cd = :status
 AND a.version_no_cd = :version AND a.tenant_cd = RPAD(:tenant, 10, ' ') AND a.branch_id = :branch AND b.n_cube_nm is NULL)
""")
        if (rows == 0)
        {
            throw new IllegalArgumentException('Cannot update test data, cube: ' + cubeName + ' does not exist in app: ' + appId)
        }
        if (rows != 1)
        {
            throw new IllegalArgumentException('Cannot update test data as more than one matches, cube: ' + cubeName + ', app: ' + appId)
        }
    }

    String getTestData(Connection c, ApplicationID appId, String cubeName)
    {
        Map map = appId as Map
        map.cube = buildName(c, cubeName)
        Sql sql = new Sql(c)
        String testData = null

        sql.eachRow(map, """\
SELECT a.test_data_bin
 FROM n_cube a
 LEFT OUTER JOIN n_cube b
 ON abs(a.revision_number) < abs(b.revision_number) AND """ + nameCompareCondition(c) + """ AND a.app_cd = b.app_cd
 AND a.status_cd = b.status_cd AND a.version_no_cd = b.version_no_cd AND a.tenant_cd = b.tenant_cd AND a.branch_id = b.branch_id
 WHERE """ + buildNameCondition(c, "a.n_cube_nm") + """ = :cube AND a.app_cd = :app AND a.status_cd = :status
 AND a.version_no_cd = :version AND a.tenant_cd = RPAD(:tenant, 10, ' ') AND a.branch_id = :branch AND b.n_cube_nm is NULL
""", { ResultSet row ->
            byte[] testBytes = row.getBytes(TEST_DATA_BIN)
            testData = testBytes == null ? "" : new String(testBytes, "UTF-8")
        })

        if (testData == null)
        {
            throw new IllegalArgumentException("Unable to fetch test data, cube: " + cubeName + ", app: " + appId + " does not exist.")
        }
        return testData
    }

    boolean updateNotes(Connection c, ApplicationID appId, String cubeName, String notes)
    {
        Map map = appId as Map
        map.putAll([notes: notes == null ? null : notes.getBytes("UTF-8"), status: ReleaseStatus.SNAPSHOT.name(),
                    cube: buildName(c, cubeName)])
        Sql sql = new Sql(c)

        int rows = sql.executeUpdate(map, """\
UPDATE n_cube SET notes_bin = :notes
WHERE n_cube_id = (SELECT a.n_cube_id
 FROM n_cube a
 LEFT OUTER JOIN n_cube b
 ON abs(a.revision_number) < abs(b.revision_number) AND """ + nameCompareCondition(c) + """ AND a.app_cd = b.app_cd
 AND a.status_cd = b.status_cd AND a.version_no_cd = b.version_no_cd AND a.tenant_cd = b.tenant_cd AND a.branch_id = b.branch_id
 WHERE """ + buildNameCondition(c, "a.n_cube_nm") + """ = :cube AND a.app_cd = :app AND a.status_cd = :status
 AND a.version_no_cd = :version AND a.tenant_cd = RPAD(:tenant, 10, ' ') AND a.branch_id = :branch AND b.n_cube_nm is NULL)
""")
        if (rows == 0)
        {
            throw new IllegalArgumentException('Cannot update notes, cube: ' + cubeName + ' does not exist in app: ' + appId)
        }
        if (rows != 1)
        {
            throw new IllegalArgumentException('Cannot update notes as more than one matches, cube: ' + cubeName + ', app: ' + appId)
        }
        return true
    }

    String getNotes(Connection c, ApplicationID appId, String cubeName)
    {
        Map map = appId as Map
        map.cube = buildName(c, cubeName)
        Sql sql = new Sql(c)
        String notes = null

        sql.eachRow(map, """\
SELECT a.notes_bin
 FROM n_cube a
 LEFT OUTER JOIN n_cube b
 ON abs(a.revision_number) < abs(b.revision_number) AND """ + nameCompareCondition(c) + """ AND a.app_cd = b.app_cd AND a.status_cd = b.status_cd AND a.version_no_cd = b.version_no_cd AND a.tenant_cd = b.tenant_cd AND a.branch_id = b.branch_id
 WHERE """ + buildNameCondition(c, "a.n_cube_nm") + """ = :cube AND a.app_cd = :app AND a.status_cd = :status AND a.version_no_cd = :version AND a.tenant_cd = RPAD(:tenant, 10, ' ') AND a.branch_id = :branch AND b.n_cube_nm is NULL
""", { ResultSet row ->
            byte[] noteBytes = row.getBytes('notes_bin')
            notes = new String(noteBytes == null ? "".getBytes() : noteBytes, "UTF-8")
        })

        if (notes == null)
        {
            throw new IllegalArgumentException("Could not fetch notes, no cube: " + cubeName + " in app: " + appId)
        }
        return notes
    }

    List<String> getAppNames(Connection c, String tenant, String status, String branch)
    {
        if (StringUtilities.isEmpty(tenant) ||
            StringUtilities.isEmpty(status) ||
            StringUtilities.isEmpty(branch))
        {
            throw new IllegalArgumentException('error calling getAppVersions(), tenant (' + tenant + '), status (' + status + '), or branch (' + branch + '), cannot be null or empty')
        }
        Map map = [tenant:tenant, status:status, branch:branch]
        Sql sql = new Sql(c)
        List<String> apps = []

        sql.eachRow("SELECT DISTINCT app_cd FROM n_cube WHERE tenant_cd = RPAD(:tenant, 10, ' ') and status_cd = :status and branch_id = :branch", map, { ResultSet row ->
            apps.add(row.getString('app_cd'))
        })
        return apps
    }

    List<String> getAppVersions(Connection c, String tenant, String app, String status, String branch)
    {
        if (StringUtilities.isEmpty(tenant) ||
            StringUtilities.isEmpty(app) ||
            StringUtilities.isEmpty(status) ||
            StringUtilities.isEmpty(branch))
        {
            throw new IllegalArgumentException('error calling getAppVersions(), tenant (' + tenant + '), app (' + app +'), status (' + status + '), or branch (' + branch + '), cannot be null or empty')
        }
        Sql sql = new Sql(c)
        Map map = [tenant:tenant, app:app, status:status, branch:branch]
        List<String> versions = []

        sql.eachRow("SELECT DISTINCT version_no_cd FROM n_cube WHERE app_cd = :app AND status_cd = :status AND tenant_cd = RPAD(:tenant, 10, ' ') and branch_id = :branch", map, { ResultSet row ->
            versions.add(row.getString('version_no_cd'))
        })
        return versions
    }

    Set<String> getBranches(Connection c, String tenant)
    {
        if (StringUtilities.isEmpty(tenant))
        {
            throw new IllegalArgumentException('error calling getBranches(), tenant must not be null or empty')
        }
        Sql sql = new Sql(c)
        Set<String> branches = new HashSet<>()

        sql.eachRow("SELECT DISTINCT branch_id FROM n_cube WHERE tenant_cd = RPAD(:tenant, 10, ' ')", [tenant:tenant], { ResultSet row ->
            branches.add(row.getString('branch_id'))
        })
        return branches
    }

    /**
     * Check for existence of a cube with this appId.  You can ignoreStatus if you want to check for existence of
     * a SNAPSHOT or RELEASE cube.
     * @param ignoreStatus - If you want to ignore status (check for both SNAPSHOT and RELEASE cubes in existence) pass
     *                     in true.
     * @return true if any cubes exist for the given AppId, false otherwise.
     */
    boolean doCubesExist(Connection c, ApplicationID appId, boolean ignoreStatus)
    {
        Map map = appId as Map
        Sql sql = new Sql(c)
        String statement = "SELECT DISTINCT n_cube_id FROM n_cube WHERE app_cd = :app AND version_no_cd = :version AND tenant_cd = RPAD(:tenant, 10, ' ') AND branch_id = :branch"

        if (!ignoreStatus)
        {
            statement += ' AND status_cd = :status'
        }

        boolean result = false
        sql.eachRow(statement, map, 0, 1, { ResultSet row -> result = true })
        return result
    }

    Long getMaxRevision(Connection c, ApplicationID appId, String cubeName)
    {
        Map map = appId as Map
        map.cube = buildName(c, cubeName)
        Sql sql = new Sql(c)
        Long rev = null

        String str = """\
SELECT a.revision_number
 FROM n_cube a
 LEFT OUTER JOIN n_cube b
 ON abs(a.revision_number) < abs(b.revision_number) AND """ + nameCompareCondition(c) + """ AND a.app_cd = b.app_cd
 AND a.status_cd = b.status_cd AND a.version_no_cd = b.version_no_cd AND a.tenant_cd = b.tenant_cd AND a.branch_id = b.branch_id
 WHERE """ + buildNameCondition(c, "a.n_cube_nm") + """ = :cube AND a.app_cd = :app AND a.status_cd = :status
 AND a.version_no_cd = :version AND a.tenant_cd = RPAD(:tenant, 10, ' ') AND a.branch_id = :branch AND b.n_cube_nm is NULL
"""
        sql.eachRow(str, map, { ResultSet row ->
            rev = row.getLong('revision_number')
        })
        return rev
    }

    Long getMinRevision(Connection c, ApplicationID appId, String cubeName)
    {
        Map map = appId as Map
        map.cube = buildName(c, cubeName)
        Sql sql = new Sql(c)
        Long rev = null

        String str = """\
SELECT a.revision_number
 FROM n_cube a
 LEFT OUTER JOIN n_cube b
 ON abs(a.revision_number) > abs(b.revision_number) AND """ + nameCompareCondition(c) + """ AND a.app_cd = b.app_cd
 AND a.status_cd = b.status_cd AND a.version_no_cd = b.version_no_cd AND a.tenant_cd = b.tenant_cd AND a.branch_id = b.branch_id
 WHERE """ + buildNameCondition(c, "a.n_cube_nm") + """ = :cube AND a.app_cd = :app AND a.status_cd = :status
 AND a.version_no_cd = :version AND a.tenant_cd = RPAD(:tenant, 10, ' ') AND a.branch_id = :branch AND b.n_cube_nm is NULL
"""
        sql.eachRow(str, map, { ResultSet row ->
            rev = row.getLong('revision_number')
        })
        return rev
    }

    protected static void getCubeInfoRecords(ApplicationID appId, Pattern searchPattern, List<NCubeInfoDto> list, ResultSet row)
    {
        boolean contentMatched = false

        if (searchPattern != null)
        {
            byte[] bytes = IOUtilities.uncompressBytes(row.getBytes(CUBE_VALUE_BIN))
            String cubeData = StringUtilities.createUtf8String(bytes)
            Matcher matcher = searchPattern.matcher(cubeData)
            contentMatched = matcher.find()
        }

        if (searchPattern == null || contentMatched)
        {
            NCubeInfoDto dto = new NCubeInfoDto()
            dto.id = Long.toString(row.getLong('n_cube_id'))
            dto.name = row.getString('n_cube_nm')
            dto.branch = appId.branch
            dto.tenant = appId.tenant
            byte[] notes = row.getBytes(NOTES_BIN)
            dto.notes = new String(notes == null ? "".getBytes() : notes, 'UTF-8')
            dto.version = appId.version
            dto.status = row.getString('status_cd')
            dto.app = appId.app
            dto.createDate = new Date(row.getTimestamp('create_dt').getTime())
            dto.createHid = row.getString('create_hid')
            dto.revision = Long.toString(row.getLong('revision_number'))
            dto.changed = row.getBoolean('changed')
            dto.sha1 = row.getString('sha1')
            dto.headSha1 = row.getString('head_sha1')
            list.add(dto)
        }
    }

    protected static NCube buildCube(ApplicationID appId, ResultSet row)
    {
        NCube ncube = NCube.createCubeFromStream(row.getBinaryStream(CUBE_VALUE_BIN))
        ncube.setSha1(row.getString("sha1"))
        ncube.setApplicationID(appId)
        return ncube
    }

    protected boolean doReleaseCubesExist(Connection c, ApplicationID appId)
    {
        return doCubesExist(c, appId.asRelease(), false)
    }

    // ------------------------------------------ local non-JDBC helper methods ----------------------------------------

    protected static String createNote(String user, Date date, String notes)
    {
        return dateTimeFormat.format(date) + ' [' + user + '] ' + notes
    }

    protected static boolean toBoolean(Object value)
    {
        if (value == null)
        {
            return false
        }
        return ((Boolean)value).booleanValue()
    }

    private static String convertPattern(String pattern)
    {
        if (StringUtilities.isEmpty(pattern) || '*'.equals(pattern))
        {
            return null
        }
        else
        {
            pattern = pattern.replace('*', '%')
            pattern = pattern.replace('?', '_')
        }
        return pattern
    }

    static String nameCompareCondition(Connection c)
    {
        if (isOracle(c))
        {
            return 'LOWER(a.n_cube_nm) = LOWER(b.n_cube_nm)'
        }
        return 'a.n_cube_nm = b.n_cube_nm'
    }

    static String buildNameCondition(Connection c, String name)
    {
        if (isOracle(c))
        {
            return ("LOWER(" + name + ")")
        }
        return name
    }

    static String buildName(Connection c, String name)
    {
        if (isOracle(c))
        {
            return name == null ? null : name.toLowerCase()
        }
        return name
    }

    static boolean isOracle(Connection c)
    {
        if (c == null)
        {
            return false
        }
        return Regexes.isOraclePattern.matcher(c.getMetaData().getDriverName()).matches()
    }
}
