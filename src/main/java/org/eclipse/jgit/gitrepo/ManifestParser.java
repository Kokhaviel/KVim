/*
 * Copyright (C) 2015, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.gitrepo;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.gitrepo.RepoProject.CopyFile;
import org.eclipse.jgit.gitrepo.RepoProject.LinkFile;
import org.eclipse.jgit.gitrepo.RepoProject.ReferenceFile;
import org.eclipse.jgit.gitrepo.internal.RepoText;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Repository;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.*;

public class ManifestParser extends DefaultHandler {
	private final String filename;
	private final URI baseUrl;
	private final String defaultBranch;
	private final Repository rootRepo;
	private final Map<String, Remote> remotes;
	private final Set<String> plusGroups;
	private final Set<String> minusGroups;
	private final List<RepoProject> projects;
	private final List<RepoProject> filteredProjects;
	private final IncludedFileReader includedReader;

	private String defaultRemote;
	private String defaultRevision;
	private int xmlInRead;
	private RepoProject currentProject;

	public interface IncludedFileReader {

		InputStream readIncludeFile(String path)
				throws GitAPIException, IOException;
	}

	public ManifestParser(IncludedFileReader includedReader, String filename,
						  String defaultBranch, String baseUrl, String groups,
						  Repository rootRepo) {
		this.includedReader = includedReader;
		this.filename = filename;
		this.defaultBranch = defaultBranch;
		this.rootRepo = rootRepo;
		this.baseUrl = normalizeEmptyPath(URI.create(baseUrl));

		plusGroups = new HashSet<>();
		minusGroups = new HashSet<>();
		if(groups == null || groups.length() == 0
				|| groups.equals("default")) {
			minusGroups.add("notdefault");
		} else {
			for(String group : groups.split(",")) {
				if(group.startsWith("-"))
					minusGroups.add(group.substring(1));
				else
					plusGroups.add(group);
			}
		}

		remotes = new HashMap<>();
		projects = new ArrayList<>();
		filteredProjects = new ArrayList<>();
	}

	public void read(InputStream inputStream) throws IOException {
		xmlInRead++;
		final XMLReader xr;
		try {
			xr = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
		} catch(SAXException | ParserConfigurationException e) {
			throw new IOException(JGitText.get().noXMLParserAvailable, e);
		}
		xr.setContentHandler(this);
		try {
			xr.parse(new InputSource(inputStream));
		} catch(SAXException e) {
			throw new IOException(RepoText.get().errorParsingManifestFile, e);
		}
	}

	@Override
	public void startElement(
			String uri,
			String localName,
			String qName,
			Attributes attributes) throws SAXException {
		if(qName == null) {
			return;
		}
		switch(qName) {
			case "project":
				if(attributes.getValue("name") == null) {
					throw new SAXException(RepoText.get().invalidManifest);
				}
				currentProject = new RepoProject(attributes.getValue("name"), attributes.getValue("path"),
						attributes.getValue("revision"), attributes.getValue("remote"), attributes.getValue("groups"));
				currentProject.setRecommendShallow(attributes.getValue("clone-depth"));
				break;
			case "remote":
				String alias = attributes.getValue("alias");
				String fetch = attributes.getValue("fetch");
				String revision = attributes.getValue("revision");
				Remote remote = new Remote(fetch, revision);
				remotes.put(attributes.getValue("name"), remote);
				if(alias != null) {
					remotes.put(alias, remote);
				}
				break;
			case "default":
				defaultRemote = attributes.getValue("remote");
				defaultRevision = attributes.getValue("revision");
				break;
			case "copyfile":
				if(currentProject == null) {
					throw new SAXException(RepoText.get().invalidManifest);
				}
				currentProject.addCopyFile(new CopyFile(rootRepo,
						currentProject.getPath(), attributes.getValue("src"), attributes.getValue("dest")));
				break;
			case "linkfile":
				if(currentProject == null) {
					throw new SAXException(RepoText.get().invalidManifest);
				}
				currentProject.addLinkFile(new LinkFile(rootRepo,
						currentProject.getPath(), attributes.getValue("src"), attributes.getValue("dest")));
				break;
			case "include":
				String name = attributes.getValue("name");
				if(includedReader != null) {
					try(InputStream is = includedReader.readIncludeFile(name)) {
						if(is == null) {
							throw new SAXException(
									RepoText.get().errorIncludeNotImplemented);
						}
						read(is);
					} catch(Exception e) {
						throw new SAXException(MessageFormat
								.format(RepoText.get().errorIncludeFile, name), e);
					}
				} else if(filename != null) {
					int index = filename.lastIndexOf('/');
					String path = filename.substring(0, index + 1) + name;
					try(InputStream is = Files.newInputStream(Paths.get(path))) {
						read(is);
					} catch(IOException e) {
						throw new SAXException(MessageFormat
								.format(RepoText.get().errorIncludeFile, path), e);
					}
				}
				break;
			case "remove-project": {
				String name2 = attributes.getValue("name");
				projects.removeIf((p) -> p.getName().equals(name2));
				break;
			}
			default:
				break;
		}
	}

	@Override
	public void endElement(String uri, String localName, String qName) {
		if("project".equals(qName)) {
			projects.add(currentProject);
			currentProject = null;
		}
	}

	@Override
	public void endDocument() throws SAXException {
		xmlInRead--;
		if(xmlInRead != 0) return;
		Map<String, URI> remoteUrls = new HashMap<>();
		if(defaultRevision == null && defaultRemote != null) {
			Remote remote = remotes.get(defaultRemote);
			if(remote != null) {
				defaultRevision = remote.revision;
			}
			if(defaultRevision == null) {
				defaultRevision = defaultBranch;
			}
		}
		for(RepoProject proj : projects) {
			String remote = proj.getRemote();
			String revision = defaultRevision;
			if(remote == null) {
				if(defaultRemote == null) {
					if(filename != null) {
						throw new SAXException(MessageFormat.format(
								RepoText.get().errorNoDefaultFilename,
								filename));
					}
					throw new SAXException(RepoText.get().errorNoDefault);
				}
				remote = defaultRemote;
			} else {
				Remote r = remotes.get(remote);
				if(r != null && r.revision != null) {
					revision = r.revision;
				}
			}
			URI remoteUrl = remoteUrls.get(remote);
			if(remoteUrl == null) {
				String fetch = remotes.get(remote).fetch;
				if(fetch == null) {
					throw new SAXException(MessageFormat
							.format(RepoText.get().errorNoFetch, remote));
				}
				remoteUrl = normalizeEmptyPath(baseUrl.resolve(fetch));
				remoteUrls.put(remote, remoteUrl);
			}
			proj.setUrl(remoteUrl.resolve(proj.getName()).toString())
					.setDefaultRevision(revision);
		}

		filteredProjects.addAll(projects);
		removeNotInGroup();
		removeOverlaps();
	}

	static URI normalizeEmptyPath(URI u) {
		if(u.getHost() != null && !u.getHost().isEmpty() &&
				(u.getPath() == null || u.getPath().isEmpty())) {
			try {
				return new URI(u.getScheme(),
						u.getUserInfo(), u.getHost(), u.getPort(),
						"/", u.getQuery(), u.getFragment());
			} catch(URISyntaxException x) {
				throw new IllegalArgumentException(x.getMessage(), x);
			}
		}
		return u;
	}

	@NonNull
	public List<RepoProject> getFilteredProjects() {
		return filteredProjects;
	}

	void removeNotInGroup() {
		filteredProjects.removeIf(repoProject -> !inGroups(repoProject));
	}

	void removeOverlaps() {
		Collections.sort(filteredProjects);
		Iterator<RepoProject> iter = filteredProjects.iterator();
		if(!iter.hasNext())
			return;
		RepoProject last = iter.next();
		while(iter.hasNext()) {
			RepoProject p = iter.next();
			if(last.isAncestorOf(p))
				iter.remove();
			else
				last = p;
		}
		removeNestedCopyAndLinkfiles();
	}

	private void removeNestedCopyAndLinkfiles() {
		for(RepoProject proj : filteredProjects) {
			List<CopyFile> copyfiles = new ArrayList<>(proj.getCopyFiles());
			proj.clearCopyFiles();
			for(CopyFile copyfile : copyfiles) {
				if(!isNestedReferencefile(copyfile)) {
					proj.addCopyFile(copyfile);
				}
			}
			List<LinkFile> linkfiles = new ArrayList<>(proj.getLinkFiles());
			proj.clearLinkFiles();
			for(LinkFile linkfile : linkfiles) {
				if(!isNestedReferencefile(linkfile)) {
					proj.addLinkFile(linkfile);
				}
			}
		}
	}

	boolean inGroups(RepoProject proj) {
		for(String group : minusGroups) {
			if(proj.inGroup(group)) {
				return false;
			}
		}
		if(plusGroups.isEmpty() || plusGroups.contains("all")) {
			return true;
		}
		for(String group : plusGroups) {
			if(proj.inGroup(group))
				return true;
		}
		return false;
	}

	private boolean isNestedReferencefile(ReferenceFile referencefile) {
		if(referencefile.dest.indexOf('/') == -1) {
			return false;
		}
		for(RepoProject proj : filteredProjects) {
			if(proj.getPath().compareTo(referencefile.dest) > 0) {
				return false;
			}
			if(proj.isAncestorOf(referencefile.dest)) {
				return true;
			}
		}
		return false;
	}

	private static class Remote {
		final String fetch;
		final String revision;

		Remote(String fetch, String revision) {
			this.fetch = fetch;
			this.revision = revision;
		}
	}
}
