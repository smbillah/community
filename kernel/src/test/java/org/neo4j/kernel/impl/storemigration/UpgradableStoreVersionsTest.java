/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.storemigration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.junit.Before;
import org.junit.Test;
import org.neo4j.helpers.UTF8;
import org.neo4j.kernel.impl.util.FileUtils;

public class UpgradableStoreVersionsTest
{
    @Before
    public void checkOperatingSystem() {
        assumeTrue( !System.getProperty( "os.name" ).startsWith( "Windows" ) );
    }

    @Test
    public void shouldAcceptTheStoresInTheSampleDatabaseAsBeingEligibleForUpgrade() throws IOException
    {
        URL legacyStoreResource = getClass().getResource( "oldformatstore/neostore" );
        File resourceDirectory = new File( legacyStoreResource.getFile() ).getParentFile();
        File workingDirectory = new File( "target/" + UpgradableStoreVersionsTest.class.getSimpleName() );

        FileUtils.deleteRecursively( workingDirectory );
        assertTrue( workingDirectory.mkdirs() );

        MigrationTestUtils.copyRecursively( resourceDirectory, workingDirectory );

        assertTrue( new UpgradableStoreVersions().storeFilesUpgradeable( workingDirectory ) );
    }

    @Test
    public void shouldRejectStoresIfOneFileHasIncorrectVersion() throws IOException
    {
        URL legacyStoreResource = getClass().getResource( "oldformatstore/neostore" );
        File resourceDirectory = new File( legacyStoreResource.getFile() ).getParentFile();
        File workingDirectory = new File( "target/" + UpgradableStoreVersionsTest.class.getSimpleName() );

        FileUtils.deleteRecursively( workingDirectory );
        assertTrue( workingDirectory.mkdirs() );

        MigrationTestUtils.copyRecursively( resourceDirectory, workingDirectory );

        changeVersionNumber( new File( workingDirectory, "neostore.nodestore.db" ), "v0.9.5" );

        assertFalse( new UpgradableStoreVersions().storeFilesUpgradeable( workingDirectory ) );
    }

    private void changeVersionNumber( File storeFile, String versionString ) throws IOException
    {
        byte[] versionBytes = UTF8.encode( versionString );
        FileChannel fileChannel = new RandomAccessFile( storeFile, "rw" ).getChannel();
        fileChannel.position( storeFile.length() - versionBytes.length );
        fileChannel.write( ByteBuffer.wrap( versionBytes ) );
        fileChannel.close();
    }
}