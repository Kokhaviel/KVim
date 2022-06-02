/*
 * Copyright (C) 2010, Mathias Kinzler <mathias.kinzler@sap.com>
 * Copyright (C) 2010, Chris Aniszczyk <caniszczyk@gmail.com>
 * Copyright (C) 2012-2013, Robin Rosenberg
 * Copyright (C) 2018-2022, Andre Bossert <andre.bossert@siemens.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.lib;

public final class ConfigConstants {

	public static final String CONFIG_CORE_SECTION = "core";
	public static final String CONFIG_BRANCH_SECTION = "branch";
	public static final String CONFIG_REMOTE_SECTION = "remote";
	public static final String CONFIG_DIFF_SECTION = "diff";
	public static final String CONFIG_DFS_SECTION = "dfs";
	public static final String CONFIG_RECEIVE_SECTION = "receive";
	public static final String CONFIG_USER_SECTION = "user";
	public static final String CONFIG_SUBMODULE_SECTION = "submodule";
	public static final String CONFIG_REBASE_SECTION = "rebase";
	public static final String CONFIG_GC_SECTION = "gc";
	public static final String CONFIG_PACK_SECTION = "pack";
	public static final String CONFIG_FETCH_SECTION = "fetch";
	public static final String CONFIG_PULL_SECTION = "pull";
	public static final String CONFIG_MERGE_SECTION = "merge";
	public static final String CONFIG_FILTER_SECTION = "filter";
	public static final String CONFIG_GPG_SECTION = "gpg";
	public static final String CONFIG_PROTOCOL_SECTION = "protocol";
	public static final String CONFIG_KEY_FORMAT = "format";
	public static final String CONFIG_KEY_PROGRAM = "program";
	public static final String CONFIG_KEY_SIGNINGKEY = "signingKey";
	public static final String CONFIG_COMMIT_SECTION = "commit";
	public static final String CONFIG_KEY_COMMIT_TEMPLATE = "template";
	public static final String CONFIG_TAG_SECTION = "tag";
	public static final String CONFIG_KEY_CLEANUP = "cleanup";
	public static final String CONFIG_KEY_GPGSIGN = "gpgSign";
	public static final String CONFIG_KEY_FORCE_SIGN_ANNOTATED = "forceSignAnnotated";
	public static final String CONFIG_KEY_COMMENT_CHAR = "commentChar";
	public static final String CONFIG_KEY_HOOKS_PATH = "hooksPath";
	public static final String CONFIG_KEY_QUOTE_PATH = "quotePath";
	public static final String CONFIG_KEY_ALGORITHM = "algorithm";
	public static final String CONFIG_KEY_AUTOCRLF = "autocrlf";
	public static final String CONFIG_KEY_AUTO = "auto";
	public static final String CONFIG_KEY_AUTOGC = "autogc";
	public static final String CONFIG_KEY_AUTOPACKLIMIT = "autopacklimit";
	public static final String CONFIG_KEY_EOL = "eol";
	public static final String CONFIG_KEY_BARE = "bare";
	public static final String CONFIG_KEY_EXCLUDESFILE = "excludesfile";
	public static final String CONFIG_KEY_ATTRIBUTESFILE = "attributesfile";
	public static final String CONFIG_KEY_FILEMODE = "filemode";
	public static final String CONFIG_KEY_LOGALLREFUPDATES = "logallrefupdates";
	public static final String CONFIG_KEY_REPO_FORMAT_VERSION = "repositoryformatversion";
	public static final String CONFIG_KEY_WORKTREE = "worktree";
	public static final String CONFIG_KEY_BLOCK_LIMIT = "blockLimit";
	public static final String CONFIG_KEY_BLOCK_SIZE = "blockSize";
	public static final String CONFIG_KEY_CONCURRENCY_LEVEL = "concurrencyLevel";
	public static final String CONFIG_KEY_SYMLINKS = "symlinks";
	public static final String CONFIG_KEY_REMOTE = "remote";
	public static final String CONFIG_KEY_PUSH_REMOTE = "pushRemote";
	public static final String CONFIG_KEY_PUSH_DEFAULT = "pushDefault";
	public static final String CONFIG_KEY_MERGE = "merge";
	public static final String CONFIG_KEY_REBASE = "rebase";
	public static final String CONFIG_KEY_URL = "url";
	public static final String CONFIG_KEY_AUTOSETUPMERGE = "autosetupmerge";
	public static final String CONFIG_KEY_AUTOSETUPREBASE = "autosetuprebase";
	public static final String CONFIG_KEY_AUTOSTASH = "autostash";
	public static final String CONFIG_KEY_ALWAYS = "always";
	public static final String CONFIG_KEY_PATH = "path";
	public static final String CONFIG_KEY_UPDATE = "update";
	public static final String CONFIG_KEY_IGNORE = "ignore";
	public static final String CONFIG_KEY_COMPRESSION = "compression";
	public static final String CONFIG_KEY_INDEXVERSION = "indexversion";
	public static final String CONFIG_KEY_HIDEDOTFILES = "hidedotfiles";
	public static final String CONFIG_KEY_DIRNOGITLINKS = "dirNoGitLinks";
	public static final String CONFIG_KEY_PRECOMPOSEUNICODE = "precomposeunicode";
	public static final String CONFIG_KEY_PRUNEEXPIRE = "pruneexpire";
	public static final String CONFIG_KEY_PRUNEPACKEXPIRE = "prunepackexpire";
	public static final String CONFIG_KEY_LOGEXPIRY = "logExpiry";
	public static final String CONFIG_KEY_AUTODETACH = "autoDetach";
	public static final String CONFIG_KEY_MERGEOPTIONS = "mergeoptions";
	public static final String CONFIG_KEY_FF = "ff";
	public static final String CONFIG_KEY_CHECKSTAT = "checkstat";
	public static final String CONFIG_KEY_RENAMELIMIT = "renamelimit";
	public static final String CONFIG_KEY_TRUSTFOLDERSTAT = "trustfolderstat";
	public static final String CONFIG_KEY_SUPPORTSATOMICFILECREATION = "supportsatomicfilecreation";
	public static final String CONFIG_KEY_NOPREFIX = "noprefix";
	public static final String CONFIG_RENAMELIMIT_COPY = "copy";
	public static final String CONFIG_RENAMELIMIT_COPIES = "copies";
	public static final String CONFIG_KEY_RENAMES = "renames";
	public static final String CONFIG_KEY_IN_CORE_LIMIT = "inCoreLimit";
	public static final String CONFIG_KEY_PRUNE = "prune";
	public static final String CONFIG_KEY_STREAM_RATIO = "streamRatio";
	public static final String CONFIG_KEY_USEJGITBUILTIN = "useJGitBuiltin";
	public static final String CONFIG_KEY_FETCH_RECURSE_SUBMODULES = "fetchRecurseSubmodules";
	public static final String CONFIG_KEY_RECURSE_SUBMODULES = "recurseSubmodules";
	public static final String CONFIG_KEY_REQUIRED = "required";
	public static final String CONFIG_SECTION_LFS = "lfs";
	public static final String CONFIG_SECTION_I18N = "i18n";
	public static final String CONFIG_KEY_COMMIT_ENCODING = "commitEncoding";
	public static final String CONFIG_FILESYSTEM_SECTION = "filesystem";
	public static final String CONFIG_KEY_TIMESTAMP_RESOLUTION = "timestampResolution";
	public static final String CONFIG_KEY_MIN_RACY_THRESHOLD = "minRacyThreshold";
	public static final String CONFIG_KEY_REF_STORAGE = "refStorage";
	public static final String CONFIG_EXTENSIONS_SECTION = "extensions";
	public static final String CONFIG_REF_STORAGE_REFTABLE = "reftable";
	public static final String CONFIG_JMX_SECTION = "jmx";
	public static final String CONFIG_KEY_BIGFILE_THRESHOLD = "bigfilethreshold";
	public static final String CONFIG_KEY_BITMAP_CONTIGUOUS_COMMIT_COUNT = "bitmapcontiguouscommitcount";
	public static final String CONFIG_KEY_BITMAP_DISTANT_COMMIT_SPAN = "bitmapdistantcommitspan";
	public static final String CONFIG_KEY_BITMAP_EXCESSIVE_BRANCH_COUNT = "bitmapexcessivebranchcount";
	public static final String CONFIG_KEY_BITMAP_INACTIVE_BRANCH_AGE_INDAYS = "bitmapinactivebranchageindays";
	public static final String CONFIG_KEY_BITMAP_RECENT_COMMIT_COUNT = "bitmaprecentcommitspan";
	public static final String CONFIG_KEY_BUILD_BITMAPS = "buildbitmaps";
	public static final String CONFIG_KEY_CUT_DELTACHAINS = "cutdeltachains";
	public static final String CONFIG_KEY_DELTA_CACHE_LIMIT = "deltacachelimit";
	public static final String CONFIG_KEY_DELTA_CACHE_SIZE = "deltacachesize";
	public static final String CONFIG_KEY_DELTA_COMPRESSION = "deltacompression";
	public static final String CONFIG_KEY_DEPTH = "depth";
	public static final String CONFIG_KEY_MIN_SIZE_PREVENT_RACYPACK = "minsizepreventracypack";
	public static final String CONFIG_KEY_REUSE_DELTAS = "reusedeltas";
	public static final String CONFIG_KEY_REUSE_OBJECTS = "reuseobjects";
	public static final String CONFIG_KEY_SINGLE_PACK = "singlepack";
	public static final String CONFIG_KEY_THREADS = "threads";
	public static final String CONFIG_KEY_WAIT_PREVENT_RACYPACK = "waitpreventracypack";
	public static final String CONFIG_KEY_WINDOW = "window";
	public static final String CONFIG_KEY_WINDOW_MEMORY = "windowmemory";
	public static final String CONFIG_FEATURE_SECTION = "feature";
	public static final String CONFIG_KEY_MANYFILES = "manyFiles";
	public static final String CONFIG_INDEX_SECTION = "index";
	public static final String CONFIG_KEY_VERSION = "version";
	public static final String CONFIG_INIT_SECTION = "init";
	public static final String CONFIG_KEY_DEFAULT_BRANCH = "defaultbranch";
	public static final String CONFIG_KEY_SEARCH_FOR_REUSE_TIMEOUT = "searchforreusetimeout";
	public static final String CONFIG_PUSH_SECTION = "push";
	public static final String CONFIG_KEY_DEFAULT = "default";

}
