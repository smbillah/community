/**
 * Copyright (c) 2002-2012 "Neo Technology,"
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
package recovery;

import static java.nio.ByteBuffer.allocate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.neo4j.helpers.collection.MapUtil.stringMap;
import static org.neo4j.kernel.Config.KEEP_LOGICAL_LOGS;
import static org.neo4j.kernel.impl.transaction.xaframework.LogIoUtils.readEntry;
import static org.neo4j.kernel.impl.transaction.xaframework.LogIoUtils.readLogHeader;
import static recovery.CreateTransactionsAndDie.produceNonCleanDbWhichWillRecover2PCsOnStartup;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.junit.Test;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.kernel.AbstractGraphDatabase;
import org.neo4j.kernel.EmbeddedGraphDatabase;
import org.neo4j.kernel.LifecycleException;
import org.neo4j.kernel.impl.transaction.xaframework.LogEntry;
import org.neo4j.kernel.impl.transaction.xaframework.LogEntry.TwoPhaseCommit;
import org.neo4j.kernel.impl.transaction.xaframework.RecoveryVerificationException;
import org.neo4j.kernel.impl.transaction.xaframework.RecoveryVerifier;
import org.neo4j.kernel.impl.transaction.xaframework.TransactionInfo;
import org.neo4j.kernel.impl.util.DumpLogicalLog.CommandFactory;

public class TestRecoveryVerification
{
    private static class TestGraphDatabase extends AbstractGraphDatabase
    {
        private final RecoveryVerifier verifier;

        TestGraphDatabase( String dir, RecoveryVerifier recoveryVerifier )
        {
            super( dir, stringMap() );
            this.verifier = recoveryVerifier;
            run();
        }
        
        @Override
        protected RecoveryVerifier createRecoveryVerifier()
        {
            return this.verifier;
        }
    }
    
    @Test
    public void recoveryVerificationShouldBeCalledForRecoveredTransactions() throws Exception
    {
        int count = 2;
        String dir = produceNonCleanDbWhichWillRecover2PCsOnStartup( "count", count );
        CountingRecoveryVerifier countingVerifier = new CountingRecoveryVerifier();
        GraphDatabaseService db = new TestGraphDatabase( dir, countingVerifier );
        assertEquals( 2, countingVerifier.count2PC );
        db.shutdown();
    }

    @Test
    public void failingRecoveryVerificationShouldThrowCorrectException() throws Exception
    {
        String dir = produceNonCleanDbWhichWillRecover2PCsOnStartup( "fail", 2 );
        RecoveryVerifier failingVerifier = new RecoveryVerifier()
        {
            @Override
            public boolean isValid( TransactionInfo txInfo )
            {
                return false;
            }
        };
        
        try
        {
            new TestGraphDatabase( dir, failingVerifier );
            fail( "Was expecting recovery exception" );
        }
        catch ( LifecycleException e )
        {
            assertEquals( RecoveryVerificationException.class, e.getCause().getClass() );
        }
    }
    
    @Test
    public void recovered2PCRecordsShouldBeWrittenInRisingTxIdOrder() throws Exception
    {
        int count = 10;
        String dir = produceNonCleanDbWhichWillRecover2PCsOnStartup( "order", count );
        // Just make it recover
        new EmbeddedGraphDatabase( dir, stringMap( KEEP_LOGICAL_LOGS, "true" ) ).shutdown();
        verifyOrderedRecords( dir, count );
    }

    private void verifyOrderedRecords( String storeDir, int expectedCount ) throws FileNotFoundException, IOException
    {
        /* Look in the .v0 log for the 2PC records and that they are ordered by txId */
        RandomAccessFile file = new RandomAccessFile( new File( storeDir, "nioneo_logical.log.v0" ), "r" );
        CommandFactory cf = new CommandFactory();
        try
        {
            FileChannel channel = file.getChannel();
            ByteBuffer buffer = allocate( 10000 );
            readLogHeader( buffer, channel, true );
            long lastOne = -1;
            int counted = 0;
            for ( LogEntry entry = null; (entry = readEntry( buffer, channel, cf )) != null; )
            {
                if ( entry instanceof TwoPhaseCommit )
                {
                    long txId = ((TwoPhaseCommit) entry).getTxId();
                    if ( lastOne == -1 ) lastOne = txId;
                    else assertEquals( lastOne+1, txId );
                    lastOne = txId;
                    counted++;
                }
            }
            assertEquals( expectedCount, counted );
        }
        finally
        {
            file.close();
        }
    }
    
    private static class CountingRecoveryVerifier implements RecoveryVerifier
    {
        private int count2PC;
        
        @Override
        public boolean isValid( TransactionInfo txInfo )
        {
            if ( !txInfo.isOnePhase() ) count2PC++;
            return true;
        }
    }
}
