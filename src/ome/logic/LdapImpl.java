/*
 * ome.logic.AdminImpl
 *
 *   Copyright 2006 University of Dundee. All rights reserved.
 *   Use is subject to license terms supplied in LICENSE.txt
 */

package ome.logic;

// Java imports
// import java.util.LinkedList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import javax.annotation.security.RolesAllowed;
import javax.ejb.Local;
import javax.ejb.Remote;
import javax.ejb.Stateless;
import javax.ejb.TransactionManagement;
import javax.ejb.TransactionManagementType;
import javax.interceptor.Interceptors;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InvalidNameException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;

import net.sf.ldaptemplate.AttributesMapper;
import net.sf.ldaptemplate.ContextMapper;
import net.sf.ldaptemplate.LdapTemplate;
import net.sf.ldaptemplate.support.DirContextAdapter;
import net.sf.ldaptemplate.support.DistinguishedName;
import net.sf.ldaptemplate.support.filter.AndFilter;
import net.sf.ldaptemplate.support.filter.EqualsFilter;

import ome.annotations.RevisionDate;
import ome.annotations.RevisionNumber;
import ome.api.ILdap;
import ome.api.ServiceInterface;
import ome.api.local.LocalLdap;
import ome.conditions.ApiUsageException;
import ome.conditions.InternalException;
import ome.model.internal.Permissions;
import ome.model.meta.Experimenter;
import ome.model.meta.ExperimenterGroup;
import ome.security.LdapUtil;
import ome.security.SecuritySystem;
import ome.services.util.OmeroAroundInvoke;

import org.jboss.annotation.ejb.LocalBinding;
import org.jboss.annotation.ejb.RemoteBinding;
import org.jboss.annotation.security.SecurityDomain;
import org.springframework.jdbc.core.simple.SimpleJdbcTemplate;
import org.springframework.jmx.support.JmxUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides methods for administering user accounts, passwords, as well as
 * methods which require special privileges.
 * 
 * Developer note: As can be expected, to perform these privileged the Admin
 * service has access to several resources that should not be generally used
 * while developing services. Misuse could circumvent security or auditing.
 * 
 * @author Josh Moore, josh.moore at gmx.de
 * @version $Revision: 1552 $, $Date: 2007-05-23 09:43:33 +0100 (Wed, 23 May
 *          2007) $
 * @see SecuritySystem
 * @see Permissions
 * @since 3.0-M3
 */
@TransactionManagement(TransactionManagementType.BEAN)
@Transactional
@RevisionDate("$Date: 2007-05-23 09:43:33 +0100 (Wed, 23 May 2007) $")
@RevisionNumber("$Revision: 1552 $")
@Stateless
@Remote(ILdap.class)
@RemoteBinding(jndiBinding = "omero/remote/ome.api.ILdap")
@Local(ILdap.class)
@LocalBinding(jndiBinding = "omero/local/ome.api.ILdap")
@SecurityDomain("OmeroSecurity")
@Interceptors( { OmeroAroundInvoke.class, SimpleLifecycle.class })
public class LdapImpl extends AbstractLevel2Service implements LocalLdap {

	protected transient LdapTemplate ldapTemplate;

	protected transient SimpleJdbcTemplate jdbc;

	/** injector for usage by the container. Not for general use */
	public final void setLdapTemplate(LdapTemplate ldapTemplate) {
		getBeanHelper().throwIfAlreadySet(this.ldapTemplate, ldapTemplate);
		this.ldapTemplate = ldapTemplate;
	}

	/** injector for usage by the container. Not for general use */
	public final void setJdbcTemplate(SimpleJdbcTemplate jdbcTemplate) {
		getBeanHelper().throwIfAlreadySet(this.jdbc, jdbcTemplate);
		jdbc = jdbcTemplate;
	}

	public final LdapTemplate getLdapTemplate() {
		return this.ldapTemplate;
	}


	// ~ System-only interface methods
	// =========================================================================

	@RolesAllowed("system")
	public List<Experimenter> searchAll() {
		EqualsFilter filter = new EqualsFilter("objectClass", "person");
		return ldapTemplate.search(DistinguishedName.EMPTY_PATH, filter
				.encode(), new PersonContextMapper());
	}

	@RolesAllowed("system")
	public List<Experimenter> searchByAttribute(DistinguishedName dn,
			String attr, String value) {
		if (attr != null && !attr.equals("") && value != null
				&& !value.equals("")) {
			AndFilter filter = new AndFilter();
			filter.and(new EqualsFilter("objectClass", "person"));
			filter.and(new EqualsFilter(attr, value));
			if (dn == null)
				dn = DistinguishedName.EMPTY_PATH;
			return ldapTemplate.search(dn, filter.encode(),
					new PersonAttributesMapper());
		} else
			return Collections.EMPTY_LIST;
	}

	@RolesAllowed("system")
	public Experimenter searchByDN(DistinguishedName dn) {
		Experimenter exp = new Experimenter();
		return (Experimenter) ldapTemplate
				.lookup(dn, new PersonContextMapper());
	}

	@RolesAllowed("system")
	public DistinguishedName findDN(String username) {
		DistinguishedName dn = new DistinguishedName();
		AndFilter filter = new AndFilter();
		filter.and(new EqualsFilter("objectClass", "person"));
		filter.and(new EqualsFilter("cn", username));
		PersonContextMapper pcm = new PersonContextMapper();
		List p = ldapTemplate.search("", filter.encode(), pcm);
		if (p.size() == 1) {
			dn = pcm.getDn();
		} else
			throw new ApiUsageException(
					"Cannot find DistinguishedName. More then one 'cn' under the specified base");
		return dn;
	}

	@RolesAllowed("system")
	public Attributes searchAttributes() {

		return null;
	}

	@RolesAllowed("system")
	public List<String> searchDnInGroups(String attr, String value) {
		if (attr != null && !attr.equals("") && value != null
				&& !value.equals("")) {
			System.out.println("searchInGroups "+attr+", "+value);
			AndFilter filter = new AndFilter();
			filter.and(new EqualsFilter("objectClass", "groupOfNames"));
			filter.and(new EqualsFilter(attr, value));
			return ldapTemplate.search("", filter.encode(),
					new GroupAttributMapper());
		} else
			return Collections.EMPTY_LIST;
	}

	@RolesAllowed("system")
	public List<Experimenter> searchByAttributes(DistinguishedName dn,
			String[] attributes, String[] values) {
		if (attributes.length != values.length)
			return Collections.EMPTY_LIST;
		AndFilter filter = new AndFilter();
		for (int i = 0; i < attributes.length; i++)
			filter.and(new EqualsFilter(attributes[i], values[i]));
		return ldapTemplate.search(dn, filter.encode(),
				new PersonAttributesMapper());
	}

	@RolesAllowed("system")
	public List<ExperimenterGroup> searchGroups() {
		// TODO Auto-generated method stub
		return null;
	}

	// ~ LOCAL PUBLIC METHODS
	// =========================================================================

	// ~ System-only interface methods
	// =========================================================================

	@RolesAllowed("system")
	public void setDN(Long experimenterID, DistinguishedName dn) {
		LdapUtil.setDNById(jdbc, experimenterID, dn.toString());
		synchronizeLoginCache();
	}

	@RolesAllowed("system")
	public void synchronizeLoginCache() {
		String string = "omero:service=LoginConfig";
		// using Spring utilities to get MBeanServer
		MBeanServer mbeanServer = JmxUtils.locateMBeanServer();
		getBeanHelper().getLogger().debug("Acquired MBeanServer.");
		ObjectName name;
		try {
			// defined in app/resources/jboss-service.xml
			name = new ObjectName(string);
			mbeanServer.invoke(name, "flushAuthenticationCaches",
					new Object[] {}, new String[] {});
			getBeanHelper().getLogger().debug("Flushed authentication caches.");
		} catch (InstanceNotFoundException infe) {
			getBeanHelper().getLogger().warn(
					string + " not found. Won't synchronize login cache.");
		} catch (Exception e) {
			InternalException ie = new InternalException(e.getMessage());
			ie.setStackTrace(e.getStackTrace());
			throw ie;
		}
	}

	// =========================================================================

	public class UidAttributMapper implements AttributesMapper {

		@RolesAllowed("system")
		public Object mapFromAttributes(Attributes attributes)
				throws NamingException {
			ArrayList l = new ArrayList();
			for (NamingEnumeration ae = attributes.getAll(); ae
					.hasMoreElements();) {
				Attribute attr = (Attribute) ae.next();
				String attrId = attr.getID();
				for (Enumeration vals = attr.getAll(); vals.hasMoreElements();) {
					DistinguishedName dn = new DistinguishedName((String) vals
							.nextElement());
					if (attrId.equals("memberUid"))
						l.add(dn);
				}
			}
			return l;
		}

	}

	public class GroupAttributMapper implements AttributesMapper {

		@RolesAllowed("system")
		public Object mapFromAttributes(Attributes attributes)
				throws NamingException {
			String groupName = null;
			if (attributes.get("cn") != null)
				groupName = (String) attributes.get("cn").get();
			return groupName;
		}

	}

	public class PersonAttributesMapper implements AttributesMapper {

		@RolesAllowed("system")
		public Object mapFromAttributes(Attributes attributes)
				throws NamingException {
			Experimenter person = new Experimenter();
			if (attributes.get("cn") != null)
				person.setOmeName(attributes.get("cn").toString());
			if (attributes.get("sn") != null)
				person.setLastName(attributes.get("sn").toString());
			if (attributes.get("givenName") != null)
				person.setFirstName(attributes.get("givenName").toString());
			return person;
		}
	}

	public class PersonContextMapper implements ContextMapper {

		private DistinguishedName dn = new DistinguishedName();

		public DistinguishedName getDn() {
			return dn;
		}

		public void setDn(DistinguishedName dn) {
			this.dn = dn;
		}

		@RolesAllowed("system")
		public Object mapFromContext(Object ctx) {
			DirContextAdapter context = (DirContextAdapter) ctx;
			try {
				dn.addAll(context.getDn());
			} catch (InvalidNameException e) {
				e.printStackTrace();
			}

			Experimenter person = new Experimenter();
			if (context.getStringAttribute("cn") != null)
				person.setOmeName(context.getStringAttribute("cn"));
			if (context.getStringAttribute("sn") != null)
				person.setLastName(context.getStringAttribute("sn"));
			if (context.getStringAttribute("givenName") != null)
				person.setFirstName(context.getStringAttribute("givenName"));
			if (context.getStringAttribute("mail") != null)
				person.setEmail(context.getStringAttribute("mail"));

			return person;
		}

	}

	public Class<? extends ServiceInterface> getServiceInterface() {
		return ILdap.class;
	}

	
	public void abc(List<Experimenter> list) {
		System.out.println("list "+list.size());
	}

}