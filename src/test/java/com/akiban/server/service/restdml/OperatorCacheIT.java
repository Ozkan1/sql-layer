package com.akiban.server.service.restdml;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.akiban.ais.model.TableName;
import com.akiban.server.explain.ExplainContext;
import com.akiban.server.service.session.Session;
import com.akiban.server.t3expressions.T3RegistryService;
import com.akiban.server.test.it.ITBase;
import com.akiban.sql.optimizer.plan.PhysicalUpdate;

public class OperatorCacheIT extends ITBase {

    public static final String SCHEMA = "test";
    private OperatorCache cache;
    @After
    public void commit() {
        this.txnService().commitTransaction(this.session());
    }
    
    @Before
    public void start() {
        Session session = this.session();
        this.txnService().beginTransaction(session);
        cache = new OperatorCache(this.serviceManager().getSchemaManager(), 
                this.serviceManager().getServiceByClass(T3RegistryService.class));
        
    }
    
    @Test
    public void testCInsert() {
        
        createTable(SCHEMA, "c",
                "cid INT PRIMARY KEY NOT NULL",
                "name VARCHAR(32)");

        TableName table = new TableName (SCHEMA, "c");
        PhysicalUpdate insert = cache.getInsertOperator(session(), table);
        
        ExplainContext context = new ExplainContext();
        
        assertEquals(
                insert.explainToString(context, table.getSchemaName()),
                "PhysicalUpdate[VARCHAR(32672) NOT NULL, VARCHAR(32672) NOT NULL]/NO_CACHE\n" +
                "  Project_Default(Field(0))\n" +
                "    Insert_Returning(INTO c)\n" +
                "      Project_Default(Field(0), Field(1))\n" +
                "        ValuesScan_Default([$1, $2])");
    }
    
    @Test
    public void testNoPKInsert() {
        
        createTable (SCHEMA, "c", 
                "cid INT NOT NULL",
                "name VARCHAR(32)");
        TableName table = new TableName (SCHEMA, "c");
        PhysicalUpdate insert = cache.getInsertOperator(session(), table);
        
        ExplainContext context = new ExplainContext();
        
        assertEquals(
                insert.explainToString(context, table.getSchemaName()),
                "PhysicalUpdate[VARCHAR(32672) NOT NULL, VARCHAR(32672) NOT NULL]/NO_CACHE\n" +
                "  Insert_Returning(INTO c)\n" +
                "    Project_Default(Field(0), Field(1), NULL)\n" +
                "      ValuesScan_Default([$1, $2])");
    }
    
    @Test
    public void testIdentityDefault() {
        createTable (SCHEMA, "c",
                "cid int NOT NULL PRIMARY KEY generated by default as identity",
                "name varchar(32) NOT NULL");
        
        TableName table = new TableName (SCHEMA, "c");
        PhysicalUpdate insert = cache.getInsertOperator(session(), table);
        
        ExplainContext context = new ExplainContext();
        
        assertEquals(
                insert.explainToString(context, table.getSchemaName()),
                "PhysicalUpdate[VARCHAR(32672) NOT NULL, VARCHAR(32672) NOT NULL]/NO_CACHE\n" +
                "  Project_Default(Field(0))\n" +
                "    Insert_Returning(INTO c)\n" +
                "      Project_Default(ifnull(Field(0), NEXTVAL('test', '_sequence-3556597')), Field(1))\n" +
                "        ValuesScan_Default([$1, $2])");
    }
    
    @Test
    public void testIdentityAlways() {
        createTable (SCHEMA, "c",
                "cid int NOT NULL PRIMARY KEY generated always as identity",
                "name varchar(32) NOT NULL");
        
        TableName table = new TableName (SCHEMA, "c");
        PhysicalUpdate insert = cache.getInsertOperator(session(), table);
        
        ExplainContext context = new ExplainContext();
        
        assertEquals(
                insert.explainToString(context, table.getSchemaName()),
                "PhysicalUpdate[VARCHAR(32672) NOT NULL, VARCHAR(32672) NOT NULL]/NO_CACHE\n" +
                "  Project_Default(Field(0))\n" +
                "    Insert_Returning(INTO c)\n" +
                "      Project_Default(NEXTVAL('test', '_sequence-3556597$1'), Field(1))\n" +
                "        ValuesScan_Default([$1, $2])");
        
    }
    
    @Test
    public void testDefaults() {
        createTable (SCHEMA, "c", 
                "cid int not null primary key default 0",
                "name varchar(32) not null default ''",
                "taxes double not null default '0.0'");
        
        TableName table = new TableName (SCHEMA, "c");
        PhysicalUpdate insert = cache.getInsertOperator(session(), table);
        
        ExplainContext context = new ExplainContext();
        
        assertEquals(
                insert.explainToString(context, table.getSchemaName()),
                "PhysicalUpdate[VARCHAR(32672) NOT NULL, VARCHAR(32672) NOT NULL, VARCHAR(32672) NOT NULL]/NO_CACHE\n" +
                "  Project_Default(Field(0))\n" +
                "    Insert_Returning(INTO c)\n" +
                "      Project_Default(ifnull(Field(0), 0), ifnull(Field(1), ''), ifnull(Field(2), 0.000000e+00))\n" +
                "        ValuesScan_Default([$1, $2, $3])");
        
    }

}
