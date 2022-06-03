package fr.kokhaviel.kvim.api;

import java.util.Arrays;
import java.util.List;

public class ColorSchemes {

	public static List<String> ADA_KEYWORDS = Arrays.asList("abort ", "abs ", "abstract ", "accept ", "access ",
			"aliased ", "all ",	"and ", "array ", "at ", "begin ", "body ", "case ", "constant ", "declare ", "delay ",
			"delta ", "digits ", "do ", "else ", "elsif ", "end ", "entry ", "exception ", "exit ", "for ", "function ",
			"generic ", "goto ", "if ", "in ", "interface ", "is ", "limited ", "loop ", "mod ", "new ", "not ", "null ",
			"of ", "or ", " others ", "out ", "overriding ", "package ", "pragma ", "private ", "procedure ", "protected ",
			"raise ", "range ", "record ", "rem ", "renames ", "requeue ", "return ", "reverse ", "select ", "separate ",
			"some ", "subtype ", "synchronized ", "tagged ", "task ", "terminate ", "then ","type ", "until ", "use ",
			"when ", "while ", "with ", "xor ");

	public static List<String> ASSEMBLY_KEYWORDS = Arrays.asList("add ", "and ", "call ", "cli ", "cmp ", "dec ", "div ",
			"hlt ", "idiv ", "imul ", "inc ", "int ", "jmp ", "lea ", "mov ", "neg ", "not ", "or ", "pop ", "push ",
			"ret ", "sub ", "xor ");

	public static List<String> BASH_KEYWORDS = Arrays.asList("alias ", "bg ", "bind ", "break ", "case ", "cd ", "command ",
			"complete ", "continue ", "do ", "done ", "echo ", "eval ", "exec ", "exit ", "export ", "false ", "fg ",
			"for ", "history ", "if ", "jobs ", "kill ", "logout ", "popd ", "pushd ", "pwd ", "read ", "return ", "set ",
			"shift ", "source ", "suspend ", "time ", "true ", "type ", "umask ", "unalias ", "unset ", "until ", "while ",
			"fi ", "esac ", "elif ", "then ");

	public static List<String> C_KEYWORDS = Arrays.asList("asm ", "auto ", "break ", "case ", "char ", "const ", "continue ", "default ",
			"do ", "double ", "else ", "enum ", "extern ", "float ", "for ", "goto ", "if ", "inline ", "int ", "long ", "register ",
			"restrict ", "return ", "short ", "signed ", "sizeof ", "static ", "struct ", "switch ", "typedef ", "union ", "unsigned ",
			"void ", "volatile ", "while ");

	public static List<String> C_SHARP_KEYWORD = Arrays.asList("abstraction ", "as ", "base ", "bool ", "break ", "byte ",
			"case ", "catch ", "char ", "checked ", "class ", "const ", "continue ", "default ", "do ", "double ", "else ", "enum ",
			"event ", "extern ", "false ", "fixed ", "float ", "for ", "foreach ", "goto ", "if ", "in ", "int ", "interface ",
			"internal ", "is ", "lock ", "long ", "namespace ", "new ", "null ", "object ", "operator ",	"out ", "override ",
			"params ", "priv ", "protected ", "public ", "readonly ", "ref ", "return ", "sbyte ", "sealed ", "short ", "sizeof ",
			"stackalloc ", "static ", "string ", "struct ", "switch ", "this ", "throw ", "true ", "try ", "typeof ", "uint ", "ulong ",
			"unchecked ", "unsafe ", "ushort ", "using ", "virtual ", "virtual ", "void ", "volatile ", "while ");

	public static List<String> CPP_KEYWORDS = Arrays.asList("alignof ", "and ", "asm ", "auto ", "bitand ", "bitor ", "bool ", "break ",
			"case ", "catch ", "char ", "class ", "const ", "continue ", "default ", "delete ", "do ", "double ", "else ", "enum ",
			"export ", "extern ", "false ", "float ", "for ", "goto ", "if ", "inline ", "int ", "long ", "mutable ", "namespace ",
			"new ", "not ", "nullptr ", "or ", "private ", "protected ", "public ", "register ", "requires ", "return ", "short ",
			"signed ", "sizeof ", "static ", "struct ", "switch ", "synchronized ", "this ", "throw ", "true ", "try ", "typedef ",
			"union ", "unsigned ", "using ", "virtual ", "void ", "volatile ", "while ", "xor ");

	public static List<String> CSS_KEYWORDS = Arrays.asList("align-content", "animation", "background", "background-color",
			"background-image", "border", "border-color", "border-spacing", "border-style", "border-width", "column-width", "display",
			"font", "font-family", "font-size", "font-style", "font-weight", "gap", "grid", "height", "left", "line-break",
			"margin", "opacity", "outline", "padding", "position", "right", "text-align", "text-decoration", "text-decoration-color",
			"text-decoration-style", "text-indent", "text-shadow", "width", "word-break");

	public static List<String> GO_KEYWORDS = Arrays.asList("break ", "case ", "chan ", "const ", "continue ", "default ", "defer ",
			"else ", "fallthrough ", "for ", "func ", "go ", "goto ", "if ", "import ", "interface ", "map ", "package ", "range ",
			"return ", "select ", "switch ", "type ", "var ");

	public static List<String> HTML_KEYWORDS = Arrays.asList("<head>", "<body>", "<title>", "<style>", "<link>", "<meta>",
			"<script>", "<a>", "<br>", "<button>", "<div>", "<font>", "<footer>", "<html>", "<img>", "<nav>", "<p>",
			"<span>", "<svg>",
			"</head>", "</body>", "</title>", "</style>", "</link>", "</meta>", "</script>", "</a>", "</br>", "</button>",
			"</div>", "</font>", "</footer>", "</html>", "</img>", "</nav>", "</p>", "</span>", "</svg>");

	public static List<String> JAVA_KEYWORD = Arrays.asList("abstract ", "assert ", "boolean ", "break ", "byte ", "case ", "catch ",
			"char ", "class ", "continue ", "default ", "do ", "double ", "else ", "enum ", "extends ", "false ", "final ", "finally ",
			"float ", "for ", "if ", "implements ", "import ", "instanceof ", "int ", "interface ", "long ", "native ", "new ", "null ", "package ",
			"permits ", "private ", "protected ", "public ", "record ", "return ", "sealed ", "short ", "static ", "super ", "switch ",
			"synchronized ", "this ", "throw ", "throws ","transient ", "true ", "try ", "var ", "void ", "volatile ", "while ", "yield ");

	public static List<String> JS_KEYWORDS = Arrays.asList("await ", "break ", "case ", "catch ", "class ", "const ", "continue ",
			"debugger ", "default ", "delete ", "do ", "else ", "enum ", "export ", "extends ", "false ", "finally ", "for ", "function ",
			"if ", "implements ",	"import ", "in ", "instanceof ", "interface ", "let ", "new ", "null ", "package ", "private ",
			"protected ", "public ", "return ", "super ", "switch ", "static ", "this ", "throw ", "try ", "true ", "typeof ", "var ",
			"void ", "while ", "with ", "yield ");

	public static List<String> KOTLIN_KEYWORDS = Arrays.asList("abstract ", "annotation ", "as ", "break ", "by ", "catch ",
			"const ", "class ", "continue ", "do ",	"else ", "enum ", "false ", "final ", "finally ", "for ", "fun ", "if ", "import ",
			"in ", "inline ", "inner ", "interface ", "is ", "null ", "object ", "package ", "private ", "protected ", "public ",
			"return ", "sealed ", "super ", "this ", "throw ", "true ", "try ", "typealias ", "typeof ", "val ", "var ", "when ", "while ");

	public static List<String> LUA_KEYWORDS = Arrays.asList("and ", "break ", "do ", "else ", "elseif ", "end ", "false ", "for ",
			"function ", "if ", "in ", "local ", "nil ", "not ", "or ", "repeat ", "return ", "then ", "true ", "until ", "while ");

	public static List<String> PHP_KEYWORDS = Arrays.asList("abstract ", "and ", "as ", "break ", "callable ", "case ", "catch ",
			"class ", "clone ", "const ", "continue ", "declare ", "default ", "do ", "echo ", "else ", "elseif ", "empty ", "enddeclare ",
			"endfor ", "endforeach ", "endif ", "endswitch ", "extends ", "final ", "finally ", "fn ", "for ", "foreach ", "function ",
			"global ", "if ", "implements ", "include ", "include_once ", "instanceof ", "insteadof ", "interface ", "isset ",
			"list ", "namespace ", "new ", "or ", "print ", "private ", "protected ", "public ", "require ", "require_once ", "return ",
			"static ", "switch ", "throw ", "trait ", "try ", "use ", "var ", "while ", "xor ", "yield ");

	public static List<String> PYTHON_KEYWORDS = Arrays.asList("and", "as", "assert", "break", "class", "continue", "def",
			"del", "elif", "else", "except", "False", "finally", "for", "from", "global", "if", "import", "in", "lambda",
			"None", "not", "or", "pass", "raise", "return", "True", "try", "while", "with", "yield");

	public static List<String> RUBY_KEYWORDS = Arrays.asList("__ENCODING__ ", "alias ", "and ", "begin ", "break ", "case ",
			"class ", "def ", "do ", "else ", "elsif ", "end ", "ensure ", "false ", "for ", "if ", "in ", "new ", "next ", "not ", "or ",
			"redo ", "retry ", "return ", "self ", "super ", "then ", "true ", "undef ", "until ", "when ", "while ", "yield ");

	public static List<String> RUST_KEYWORDS = Arrays.asList("as ", "break ", "const ", "continue ", "crate ", "else ", "enum ",
			"extern ", "false ", "fn ", "for ", "if ", "impl ", "in" , "let ", "loop ", "match ", "mod ", "move ", "mut ", "pub ", "ref ",
			"return ", "self ", "static ", "struct ", "super ", "trait ", "true ", "type ", "unsafe ", "use ", "where ", "while ");

	public static List<String> SCALA_KEYWORD = Arrays.asList("abstract ", "case ", "catch ", "class ", "def ", "do ", "else ",
			"extends ", "false ", "final ", "finally ",	"for ", "forSome ", "if ", "implicit ", "import ", "lazy ", "match ", "new ",
			"null ", "object ", "override ", "package ", "private ", "protected ", "return ", "sealed ", "super ", "this ", "throw ",
			"trait ", "true ", "try ", "type ", "val ", "var ", "while ", "with ", "yield ");

	public static List<String> SQL_KEYWORDS = Arrays.asList("ADD ", "ALL ", "ALTER ", "AND ", "AS ", "ASC ", "BETWEEN ", "CASE ",
			"COLUMN ", "CREATE ", "DATABASE ", "DEFAULT ", "DELETE ", "DESC ", "DISTINCT ", "DROP ", "EXEC ", "EXISTS ", "FROM ",
			"HAVING ", "IN ", "INDEX ", "INSERT ", "INTO ", "JOIN ", "LIKE ", "LIMIT ", "NOT ", "NULL ", "OR ", "ORDER ", "PROCEDURE ",
			"SELECT ", "SET ", "TABLE ", "TRUNCATE ", "UNION ", "UPDATE ", "VALUES ", "WHERE ");

	public static List<String> SWIFT_KEYWORDS = Arrays.asList("Any ", "as ", "associatedtype ", "break ", "case ", "catch ",
			"class ", "continue ", "default ", "defer ", "deinit ", "do ", "else ", "enum ", "extension ", "fallthrough ", "false ",
			"fileprivate ", "for ", "func ", "guard ", "if ", "import ", "in ", "init ", "inout ", "internal ", "is ", "let ", "nil ",
			"open ", "operator ", "private ", "precedencegroup ", "protocol ", "public ", "repeat ", "return ", "rethrows ", "self ",
			"Self ", "static ", "struct ", "subscript ", "super ", "switch ", "throw ", "throws ", "true ", "try ", "typealias ", "var ", "where ", "while ");
}
