package fr.kokhaviel.kvim.api.props;

public class DefaultKVimProperties {

	public static final KVimProperties DEFAULT_RECENT_FILES_PROPERTIES = new KVimProperties(false);
	public static final KVimProperties DEFAULT_LAST_OPEN_PROPERTIES = new KVimProperties(false);
	public static final KVimProperties DEFAULT_WORKSPACE_PROPERTIES = new KVimProperties(false);

	public static void init() {
		DEFAULT_RECENT_FILES_PROPERTIES.put("name_1", "");
		DEFAULT_RECENT_FILES_PROPERTIES.put("path_1", "");
		DEFAULT_RECENT_FILES_PROPERTIES.put("name_2", "");
		DEFAULT_RECENT_FILES_PROPERTIES.put("path_2", "");
		DEFAULT_RECENT_FILES_PROPERTIES.put("name_3", "");
		DEFAULT_RECENT_FILES_PROPERTIES.put("path_3", "");
		DEFAULT_RECENT_FILES_PROPERTIES.put("name_4", "");
		DEFAULT_RECENT_FILES_PROPERTIES.put("path_4", "");
		DEFAULT_RECENT_FILES_PROPERTIES.put("name_5", "");
		DEFAULT_RECENT_FILES_PROPERTIES.put("path_5", "");
		DEFAULT_LAST_OPEN_PROPERTIES   .put("height", "400");
		DEFAULT_LAST_OPEN_PROPERTIES   .put("width",  "800");
		DEFAULT_LAST_OPEN_PROPERTIES   .put("x",  "100");
		DEFAULT_LAST_OPEN_PROPERTIES   .put("y",  "100");
	}
}
