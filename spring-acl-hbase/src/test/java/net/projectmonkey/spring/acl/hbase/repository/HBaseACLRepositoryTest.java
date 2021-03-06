package net.projectmonkey.spring.acl.hbase.repository;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.projectmonkey.spring.acl.hbase.repository.HBaseACLRepository;

import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.HTablePool;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.security.acls.domain.AclAuthorizationStrategy;
import org.springframework.security.acls.domain.AclAuthorizationStrategyImpl;
import org.springframework.security.acls.domain.AuditLogger;
import org.springframework.security.acls.domain.BasePermission;
import org.springframework.security.acls.domain.ConsoleAuditLogger;
import org.springframework.security.acls.domain.GrantedAuthoritySid;
import org.springframework.security.acls.domain.ObjectIdentityImpl;
import org.springframework.security.acls.domain.PrincipalSid;
import org.springframework.security.acls.domain.SimpleAcl;
import org.springframework.security.acls.model.AccessControlEntry;
import org.springframework.security.acls.model.Acl;
import org.springframework.security.acls.model.AclCache;
import org.springframework.security.acls.model.MutableAcl;
import org.springframework.security.acls.model.ObjectIdentity;
import org.springframework.security.acls.model.Sid;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/*
	Copyright 2012 Andy Moody
	
	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at
	
	    http://www.apache.org/licenses/LICENSE-2.0
	
	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.
*/

public class HBaseACLRepositoryTest extends AbstractHBaseRepositoryTest {

	private static final String SOME_PRINCIPAL = "some principal";
	private static final String SOME_AUTHORITY = "Some Authority";
	protected static final String TEST_TABLE_NAME = "test_acls";
	private final AclAuthorizationStrategy authorizationStrategy = new AclAuthorizationStrategyImpl(
			new SimpleGrantedAuthority(SOME_AUTHORITY));
	private final AuditLogger auditLogger = new ConsoleAuditLogger();
	private HBaseACLRepository underTest;
	private final AclCache cache = new TestingInMemoryCache();

	@BeforeClass
	public static void setUpHBase() throws IOException {
		createTables(getTables());
	}

	@AfterClass
	public static void clearHBase() throws IOException {
		deleteTables(getTables());
	}

	@Before
	public void setUp() {
		setUpAuthorisedUser();
		final HTablePool pool = getPool();
		underTest = new HBaseACLRepository(pool, auditLogger, authorizationStrategy, cache){
			@Override
			protected HTableInterface getTable() {
				return pool.getTable(TEST_TABLE_NAME);
			}
		};
	}

	@After
	public void clearTables() throws IOException {
		SecurityContextHolder.clearContext();
		clearAllTables(getTables());
	}

	@Test
	public void retrievingACLValuesWithNoSidsSpecified() {
		Acl acl1 = createAcl("id1");
		createAcl("id2");
		Acl acl3 = createAcl("id3");

		ObjectIdentity oid1 = acl1.getObjectIdentity();
		ObjectIdentity oid3 = acl3.getObjectIdentity();

		Map<ObjectIdentity, Acl> returned = underTest.getAclsById(Arrays.asList(oid1, oid3), null);

		assertEquals(2, returned.size());

		Acl returnedAcl1 = returned.get(oid1);
		assertEquals(acl1, returnedAcl1);

		Acl returnedAcl3 = returned.get(oid3);
		assertEquals(acl3, returnedAcl3);
	}

	@Test
	public void retrievingACLValuesWithSomeSidsSpecifiedLoadsAllRelevantAclsRegardlessOfWhetherACEsExistForTheSids() {
		Acl acl1 = createAcl("id1");
		createAcl("id2");
		Acl acl3 = createAcl("id3");

		ObjectIdentity oid1 = acl1.getObjectIdentity();
		ObjectIdentity oid3 = acl3.getObjectIdentity();
		
		PrincipalSid owner = new PrincipalSid(SOME_PRINCIPAL); // the owner is taken from the currently logged in user
		
		List<Sid> sids = Arrays.<Sid> asList(new PrincipalSid(SOME_PRINCIPAL));
		Map<ObjectIdentity, Acl> returned = underTest.getAclsById(Arrays.asList(oid1, oid3), sids);

		assertEquals(2, returned.size());

		Acl returnedAcl1 = returned.get(oid1);
		SimpleAcl expectedAcl1 = new SimpleAcl(acl1.getObjectIdentity(), owner, acl1.getEntries(), sids, null);
		assertEquals(expectedAcl1, returnedAcl1);

		Acl returnedAcl3 = returned.get(oid3);
		SimpleAcl expectedAcl3 = new SimpleAcl(acl3.getObjectIdentity(), owner, acl3.getEntries(), sids, null);
		assertEquals(expectedAcl3, returnedAcl3);
	}
	
	@Test
	public void acesAreReturnedInTheOrderTheyWerePriorToPersistence() {
		SimpleAcl acl = createAcl("id1");
		acl.insertAce(UUID.randomUUID(), 0, BasePermission.WRITE, new GrantedAuthoritySid("another authority"), true);
		acl.insertAce(UUID.randomUUID(), 0, BasePermission.READ, new GrantedAuthoritySid("another authority"), true);
		
		assertEquals(3, acl.getEntries().size());
		
		underTest.update(acl);
		
		ObjectIdentity oid1 = acl.getObjectIdentity();
		
		PrincipalSid owner = new PrincipalSid(SOME_PRINCIPAL); // the owner is taken from the currently logged in user
		
		Acl returned = underTest.getAclById(oid1);
		
		SimpleAcl expectedAcl = new SimpleAcl(acl.getObjectIdentity(), owner, acl.getEntries(), null, null);
		assertEquals(expectedAcl, returned);
		assertEquals(acl.getEntries().get(0), returned.getEntries().get(0));
		assertEquals(acl.getEntries().get(1), returned.getEntries().get(1));
		assertEquals(acl.getEntries().get(2), returned.getEntries().get(2));
	}

	@Test
	public void create() {
		ObjectIdentityImpl id = new ObjectIdentityImpl(HBaseACLRepository.class, "id1");
		MutableAcl acl1 = underTest.create(id);
		assertNotNull(acl1);
		assertTrue(underTest.isThereAnAclFor(id));
	}
	
	@Test
	public void delete() {
		ObjectIdentityImpl id = new ObjectIdentityImpl(HBaseACLRepository.class, "id1");
		underTest.create(id);
		assertTrue(underTest.isThereAnAclFor(id));
		
		underTest.delete(id);
		
		assertFalse(underTest.isThereAnAclFor(id));
	}
	
	@Test
	public void update(){
		ObjectIdentityImpl id = new ObjectIdentityImpl(HBaseACLRepository.class, "id1");
		MutableAcl acl1 = underTest.create(id);
		assertNotNull(acl1);
		assertTrue(underTest.isThereAnAclFor(id));
		
		acl1.insertAce(0, BasePermission.CREATE, new PrincipalSid(SOME_PRINCIPAL), true);
		underTest.update(acl1);
		
		Acl returned = underTest.getAclById(id);
		
		List<AccessControlEntry> entries = returned.getEntries();
		assertEquals(1, entries.size());
	}
	
	private SimpleAcl createAcl(final String id) {
		ObjectIdentityImpl objectIdentity = new ObjectIdentityImpl(HBaseACLRepository.class, id);
		SimpleAcl acl = (SimpleAcl) underTest.create(objectIdentity);
		acl.insertAce(UUID.randomUUID(), 0, BasePermission.CREATE, new GrantedAuthoritySid(id + "Authority"), true);
		underTest.update(acl);
		return acl;
	}
	
	private void setUpAuthorisedUser() {
		TestingAuthenticationToken authentication = new TestingAuthenticationToken(SOME_PRINCIPAL, "credentials", SOME_AUTHORITY);
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}

	private static Map<String, List<String>> getTables() {
		Map<String, List<String>> tables = new HashMap<String, List<String>>();
		tables.put(TEST_TABLE_NAME, asList(string(HBaseACLRepository.ACE_FAMILY), string(HBaseACLRepository.ACL_FAMILY)));
		return tables;
	}
	
	private static String string(final byte[] bytes){
		return new String(bytes);
	}

}
