package com.planet_ink.emutil;

import java.io.*;
import java.util.*;
import java.util.regex.*;

/*
Copyright 2026-2026 Bo Zimmerman

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
/**
 * Converts GeoProgrammer (geoAssembler) 6502 assembly source code stored in
 * a GeoWrite .CVT document into cc65 (ca65) assembler source.  The source is
 * read page by page via {@link GeoRWriter} and translated line by line.
 *
 * <p>Key translations: section directives (.psect/.ramsect/.zsect) to named
 * ca65 segments, numeric local labels (N$) to cheap locals (@N), low/high
 * byte operators ([ ] to &lt; &gt;), == equates to :=, pass-1 guard wrappers
 * removed, macro includes remapped, and GEOS-only directives removed or
 * commented.  Instructions and standard data directives pass through unchanged.
 *
 * <p>Usage: {@code java -jar bin/GeoAsmConv.jar <source.cvt> [output.s] [--srcdir dir]...}
 * or, to convert and build a whole application, {@code --lnk <manifest.lnk.cvt>}.
 */
public class GeoAsmConv
{
	private static final Pattern	DIRECTIVE_PATTERN
		= Pattern.compile("(?i)(?<![A-Za-z0-9_.])(\\.[A-Za-z0-9][A-Za-z0-9_]*)");
	private static final Pattern	STRING_PATTERN
		= Pattern.compile("\"(?:[^\"]|\"\")*\"");
	private static final Pattern	PLACEHOLDER_PATTERN
		= Pattern.compile("\u0001(\\d+)\u0001");
	private static final Pattern	NUMERIC_LOCAL_PATTERN
		= Pattern.compile("(?<![0-9A-Za-z_$])([0-9]+)\\$(?![0-9A-Za-z_$])");
	/** geoAssembler numeric-local definitions may omit the trailing colon
	 *  ("13$ cmp ..." defines local 13); ca65 needs a colon after @13. */
	private static final Pattern	NUMERIC_LOCAL_DEF_PATTERN
		= Pattern.compile("(?m)^(\\s*)([0-9]+)\\$(?![0-9A-Za-z_$])(?!:)");
	private static final Pattern	DOUBLE_EQ_PATTERN
		= Pattern.compile("(?<![!=])==(?!=)");
	/** geoAssembler counted only the first 8 characters of a symbol as
	 *  significant (case-significantly); mirror that for ca65. */
	private static final Pattern	TRUNC8_PATTERN
		= Pattern.compile("(?<![A-Za-z0-9_.@])([A-Za-z_][A-Za-z0-9_]{7})[A-Za-z0-9_]*");
	private static final Pattern	EQUATE_PATTERN
		= Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*(==|=)(.*)");
	private static final Pattern	PASS1_PATTERN
		= Pattern.compile("(?i)\\bPass1\\b");
	private static final Set<String> OPCODES = new HashSet<String>(Arrays.asList(
		"lda","sta","ldx","stx","ldy","sty","adc","sbc","and","ora","eor",
		"cmp","cpx","cpy","inc","dec","asl","lsr","rol","ror","bne","beq",
		"bcc","bcs","bvc","bvs","bpl","bmi","jmp","jsr","rts","rti","pha",
		"pla","php","plp","tax","tay","txa","tya","tsx","txs","inx","iny",
		"dex","dey","clc","sec","cli","sei","cld","sed","nop","brk",
		"clv","bit"
	));

	/** Opcodes that are valid with no operand (everything else needs one; a bare
	 *  "jsr" is a source-doc fragment and ca65 rejects it). */
	private static final Set<String> NO_OPERAND_OPCODES = new HashSet<String>(Arrays.asList(
		"tax","tay","txa","tya","tsx","txs","inx","iny","dex","dey",
		"clc","sec","cli","sei","cld","sed","nop","brk","rti","rts",
		"php","pla","pha","plp","clv"
	));

	private static final Set<String> KNOWN_NOOP = new HashSet<String>(Arrays.asList(
		".byte",".word",".dbyt",".dword",".addr",".faraddr",".res",
		".org",".if",".else",".elseif",".endif",".ifdef",".ifndef",
		".ifconst",".ifref",".ifblank",".include",".incbin",
		".ascii",".asciiz",".macpack",".proc",".endproc",
		".repeat",".endrepeat",".end",".segment",".local",".global",
		".export",".import",".importzp",".exportzp",".feature",
		".charmap",".setcpu",".zeropage",".bss",".code",".data",
		".rodata",".scope",".endscope",".block"
	));

	private static final Pattern MACRO_HEADER_PATTERN = Pattern.compile(
			"(?i)\\s*\\.macro\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*,?\\s*(.*)");

	private static final Pattern OPERAND_MATCH = Pattern.compile("^\\S+\\s+(.+)$");

	/** Environment variable listing default source directories (platform
	 *  path-separator delimited) to search for .CVT source documents. */
	private static final String SRCDIR_ENV = "GEOS_SRCDIR";
	/** Environment variable naming an explicit cc65 linker config to use. */
	private static final String CFG_ENV = "CC65_CFG";
	/** Environment variable naming the cc65 installation root. */
	private static final String CC65_HOME_ENV = "CC65_HOME";
	/** cc65 linker config basename for GEOS CBM targets. */
	private static final String GEOS_CFG_FILE = "geos-cbm.cfg";

	/** 
	 * True when a source document declares no code section (no .psect/.ramsect):
	 * it only supplies equates and/or .macro definitions.  Such a doc is a
	 * definitions doc -- its macros are parsed and its equates are made visible,
	 * but it is never emitted as a code module.  Whether a doc is a definitions
	 * doc is decided by its content, never by its file name.
	 *
	 * @param lines the raw geoAssembler source lines
	 */
	private static boolean isDefinitionsDoc(final List<String> lines)
	{
		for(final String line : lines)
		{
			final String t = stripComment(line).trim().toLowerCase();
			if(t.startsWith(".psect") || t.startsWith(".ramsect"))
				return false;
		}
		return true;
	}

	/** 
	 * Resolve an .include operand to its .cvt doc, preferring an exact key match
	 * and falling back to the loose prefix/superset match.
	 *
	 * @param operand the .include operand as written in the source
	 * @param srcdirs the directories to search
	 */
	private static File resolveInclude(final String operand, final List<File> srcdirs)
	{
		File f = resolveDocExact(operand, srcdirs);
		if(f == null)
			f = resolveDoc(operand, srcdirs);
		return f;
	}

	/**
	 * The bare (unquoted) .include operands of a source doc, as
	 * {@code {operand, key}} pairs.
	 *
	 * @param doc the source document
	 */
	private static List<String[]> bareIncludes(final File doc) throws Exception
	{
		final List<String[]> out = new ArrayList<String[]>();
		final GeoRWriter mdoc = new GeoRWriter(doc, false);
		final Pattern p = Pattern.compile("(?i)^\\.include\\s*(.*)$");
		for(final String line : extractLines(mdoc))
		{
			final String ct = stripComment(line).trim();
			if(!ct.toLowerCase().startsWith(".include"))
				continue;
			final Matcher m = p.matcher(ct);
			final String inc = m.find() ? m.group(1).trim() : "";
			if(inc.length() == 0 || inc.charAt(0) == '"')
				continue;
			out.add(new String[] { inc, inc.toLowerCase().replaceAll("[^a-z0-9]", "") });
		}
		return out;
	}

	/** 
	 * The generated ca65 include name for a definitions doc, derived from the
	 * .include operand as the source wrote it (e.g. "GEOSequates" becomes
	 * "GEOSequates.inc").  No file name is hardcoded.
	 *
	 * @param operand the .include operand naming the definitions doc
	 */
	private static String definitionsIncludeName(final String operand)
	{
		String base = operand.trim();
		final int slash = base.lastIndexOf('/');
		if(slash >= 0)
			base = base.substring(slash + 1);
		if(base.toLowerCase().endsWith(".cvt"))
			base = base.substring(0, base.length() - 4);
		return base + ".inc";
	}

	/** 
	 * Source directories supplied by the GEOS_SRCDIR environment variable,
	 * one or more directories separated by the platform path separator.
	 */
	private static List<File> environmentSourceDirs()
	{
		final List<File> out = new ArrayList<File>();
		final String env = System.getenv(SRCDIR_ENV);
		if(env != null)
			for(final String part : env.split(Pattern.quote(File.pathSeparator)))
			{
				final String t = part.trim();
				if(t.length() > 0)
					out.add(new File(t));
			}
		return out;
	}

	/**
	 * True when the line is a genuine label definition ("Name:", "Name::",
	 * "Name: opcode ...", "Name: .directive ...", possibly glued to the token).
	 * Prose such as "by: Bo Zimmerma" (a text heading, not code) is rejected.
	 *
	 * @param trimmed the trimmed source line to test
	 * @param st the current conversion state (for macro-name recognition)
	 */
	private static boolean isLabelDef(final String trimmed, final State st)
	{
		final Matcher lm = Pattern.compile("^([A-Za-z_][A-Za-z0-9_$]*)\\s*:+").matcher(trimmed);
		if(!lm.find())
			return false;
		final String rest = trimmed.substring(lm.end()).trim();
		if(rest.length() == 0)
			return true;
		final String head = rest.split("\\s+")[0];
		if(head.startsWith("."))
			return true;
		return OPCODES.contains(head.toLowerCase())
			|| st.macroNames.contains(head.toLowerCase());
	}

	/** 
	 * Detect a source-doc line that fuses two statements ("ArowTyp:\t.res\t1:
	 * \t.byte $18,...") by page damage, and split it into the separate parts for
	 * re-dispatch.  Trigger requires two directive tokens whose separator is a
	 * ':' followed only by whitespace, so ordinary single-statement lines pass.
	 *
	 * @param codePart the code portion of the line (comment removed)
	 * @param st the current conversion state
	 */
	private static List<String> splitFusedStatements(final String codePart, final State st)
	{
		if(st.macroDepth > 0)
			return null;
		final Matcher dm = DIRECTIVE_PATTERN.matcher(codePart);
		if(!dm.find())
			return null;
		final int d1e = dm.end();
		if(!dm.find())
			return null;
		final int d2s = dm.start();
		final String between = codePart.substring(d1e, d2s);
		final int colon = between.lastIndexOf(':');
		if(colon < 0)
			return null;
		if(between.substring(colon + 1).trim().length() > 0)
			return null;
		final List<String> out = new ArrayList<String>(2);
		String p1 = codePart.substring(0, d1e + colon).trim();
		if(p1.endsWith(":"))
			p1 = p1.substring(0, p1.length() - 1).trim();
		if(p1.length() == 0)
			return null;
		out.add(p1);
		out.add(codePart.substring(d2s));
		return out;
	}

	/** 
	 * geoAssembler numeric locals ("10$:" = ca65 "@10:") are scoped to the
	 * current routine (everything between two real labels) and may be referenced
	 * FORWARD ("blt 10$" branching to a later "10$:" in the same routine).
	 * ca65 numeric locals resolve only backward and reset their scope at every
	 * real label -- and at no named label we may add -- so no `@NN` scheme can
	 * express the original semantics.  Rewrite every numeric local as a plain
	 * symbolic label: each "@NN:" definition gets a unique name, and each
	 * reference is bound to its nearest "@NN:" definition within the routine
	 * (nearest PRECEDING, else nearest FOLLOWING) -- the geoAssembler rule.
	 *
	 * @param outLines the output lines to rewrite in place
	 */
	private static void fixForwardNumericLocals(final List<String> outLines)
	{
		final int n = outLines.size();
		if(n == 0)
			return;
		final Pattern defP = Pattern.compile("^\\s*@([0-9]+):");
		final Pattern refP = Pattern.compile("(?<![\\w$])@([0-9]+)(?![0-9])(?!:)");
		final Pattern bndP = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]*:.*");
		final List<Integer> routineOf = new ArrayList<Integer>(n);
		final Map<Integer, String> defNN = new HashMap<Integer, String>();     // line -> "NN"
		final Map<Integer, Integer> defOrd = new HashMap<Integer, Integer>();  // line -> 1-based ordinal
		int routine = 0;
		int ordinal = 0;
		for(int i = 0; i < n; i++)
		{
			final String t = outLines.get(i).trim();
			if(bndP.matcher(t).matches())
				routine++;
			routineOf.add(routine);
			final Matcher dm = defP.matcher(t);
			if(dm.find())
			{
				ordinal++;
				defNN.put(i, dm.group(1));
				defOrd.put(i, ordinal);
			}
		}
		if(defNN.isEmpty())
		{
			// no numeric-local definitions anywhere: every "@NN" reference is a
			// page-damage fragment with no resolvable target -- its line is dead.
			final Pattern dref = Pattern.compile("(?<![\\w$])@([0-9]+)(?!:[0-9])");
			for(int i = 0; i < n; i++)
			{
				final String t = outLines.get(i);
				if((t.indexOf('@') < 0) || !dref.matcher(t).find())
					continue;
				final String[] tt = t.split("\\t", 2);
				String hdr = "code";
				if(tt.length == 2 && tt[1] != null && tt[1].length() > 0)
					hdr = tt[1].trim();
				outLines.set(i, "; [" + hdr + "] removed; unresolvable numeric-local reference (page damage)");
			}
			return;
		}
		final List<Integer> defLines = new ArrayList<Integer>(defNN.keySet());
		Collections.sort(defLines);
		int patched = 0;
		final List<Object[]> spans = new ArrayList<Object[]>(); // {start, end, text}
		for(int i = 0; i < n; i++)
		{
			final String t = outLines.get(i);
			if(t.indexOf('@') < 0)
				continue;
			spans.clear();
			final int myRoutine = routineOf.get(i);
			final Matcher dm = defP.matcher(t);
			if(dm.find()) // "@NN:" definition on this line -> symbolic name
			{
				final Integer od = defOrd.get(i);
				final String name = od == null ? null : ("Rloc" + dm.group(1) + "_" + od.intValue());
				if(name != null)
					spans.add(new Object[]{ Integer.valueOf(dm.start()), Integer.valueOf(dm.end() - 1), name });
			}
			boolean lineIsDead = false;
			final Matcher rm = refP.matcher(t);
			while(rm.find())
			{
				final String nn = rm.group(1);
				// nearest PRECEDING @NN: in the same routine
				String bound = null;
				for(int k = defLines.size() - 1; k >= 0; k--)
				{
					final int d = defLines.get(k).intValue();
					if(d >= i)
						continue;
					if(routineOf.get(d) != myRoutine)
						break;                 // crossed into an earlier routine
					if(defNN.get(d).equals(nn))
					{
						bound = "Rloc" + nn + "_" + defOrd.get(d).intValue();
						break;
					}
				}
				if(bound == null)
				{
					// nearest FOLLOWING @NN: in the same routine
					for(int k = 0; k < defLines.size(); k++)
					{
						final int d = defLines.get(k).intValue();
						if(d <= i)
							continue;
						if(routineOf.get(d) != myRoutine)
							break;             // crossed into a later routine
						if(defNN.get(d).equals(nn))
						{
							bound = "Rloc" + nn + "_" + defOrd.get(d).intValue();
							break;
						}
					}
				}
				if(bound == null)
				{
					// neither direction offers a definition: the routine's label
					// was lost to page damage -- the line is dead source.
					lineIsDead = true;
					continue;
				}
				spans.add(new Object[]{ Integer.valueOf(rm.start()), Integer.valueOf(rm.end()), bound });
			}
			if(lineIsDead)
			{
				final String[] tt = t.split("\\t", 2);
				String hdr = "code";
				if(tt.length == 2 && tt[1] != null && tt[1].length() > 0)
					hdr = tt[1].trim();
				outLines.set(i, "; [" + hdr + "] removed; unresolvable numeric-local reference (page damage)");
				continue;
			}
			if(!spans.isEmpty())
			{
				final StringBuilder sb = new StringBuilder(t);
				for(int k = spans.size() - 1; k >= 0; k--)
				{
					final Object[] s = spans.get(k);
					sb.replace(((Integer)s[0]).intValue(), ((Integer)s[1]).intValue(), (String)s[2]);
				}
				outLines.set(i, sb.toString());
				patched += spans.size();
			}
		}
		if(patched > 0)
			System.out.println("GeoAsmConv: bound " + patched
				+ " numeric-local reference(s) to symbolic labels");
	}

	/**
	 *  In .res/.block count operands, page damage can fuse a fragment of the next
	 * source line ("TheEnd: .res 1 beq @11", ".block 2pid, redundant chec").
	 * Keep the leading numeric count and comment the junk; arithmetic
	 * ("160-117") and hex ("$90") counts are left intact.
	 *
	 * @param code the source line possibly holding a .res/.block operand
	 */
	private static String salvageResOperand(final String code)
	{
		final Matcher m = Pattern.compile("(?i)(\\.(?:res|block)\\b\\s*)([^\\r\\n]+)").matcher(code);
		if(!m.find())
			return code;
		final String op = m.group(2);
		final Matcher nm = Pattern.compile("^\\s*([0-9]+(?:\\s*[-+*]\\s*[0-9]+)*)").matcher(op);
		if(!nm.find())
			return code;
		final String tail = op.substring(nm.end());
		if(!tail.matches("^\\s*[A-Za-z_].*"))
			return code;
		return code.substring(0, m.start()) + m.group(1) + nm.group(1).trim()
			+ "\t; [trailing source-doc fragment stripped]" + code.substring(m.end());
	}

	/** A parsed project .macro definition (from an included definitions doc). */
	private static final class MacroInfo
	{
		final String name;
		final List<String> params = new ArrayList<String>();
		final List<String> body = new ArrayList<String>();
		final Set<Integer> immediateParams = new TreeSet<Integer>();
		MacroInfo(final String name)
		{
			this.name = name;
		}
	}

	/**
	 * Parse all .macro definitions from raw geoAssembler source lines.
	 *
	 * @param lines the raw source lines to scan
	 */
	private static Map<String, MacroInfo> parseMacros(final List<String> lines)
	{
		final Map<String, MacroInfo> macros = new LinkedHashMap<String, MacroInfo>();
		for(int i = 0; i < lines.size(); i++)
		{
			final String trimmed = stripComment(lines.get(i)).trim();
			if(trimmed.length() == 0)
				continue;
			final Matcher mh = MACRO_HEADER_PATTERN.matcher(trimmed);
			if(!mh.find())
				continue;
			final MacroInfo info = new MacroInfo(mh.group(1));
			final String[] params = mh.group(2).split("\\s*,\\s*");
			for(final String p : params)
			{
				if(p.length() > 0)
					info.params.add(p);
			}
			i++;
			for(; i < lines.size(); i++)
			{
				final String b = stripComment(lines.get(i)).trim();
				if(b.length() == 0)
					continue;
				if(b.equalsIgnoreCase(".endm") || b.equalsIgnoreCase(".endmacro"))
					break;
				info.body.add(b);
			}
			// Determine which parameters are used immediately (after a '#').
			for(int pi = 0; pi < info.params.size(); pi++)
			{
				final String p = info.params.get(pi);
				final Pattern immedP = Pattern.compile(
					"(?i)#[<\\( \\[\\]\\)>]*\\b" + Pattern.quote(p) + "(?![A-Za-z0-9_])");
				for(final String bl : info.body)
				{
					if(immedP.matcher(bl).find())
					{
						info.immediateParams.add(pi);
						break;
					}
				}
			}
			macros.put(info.name.toLowerCase(), info);
		}
		return macros;
	}

	/**
	 * Return the code part of a line, removed of any trailing comment.
	 *
	 * @param line the source line
	 */
	private static String stripComment(final String line)
	{
		final int ci = findCommentStart(line);
		return (ci < 0) ? line : line.substring(0, ci);
	}

	/** 
	 * Split an operand list at top-level commas (paren/quote aware).
	 *
	 * @param text the comma-separated operand text
	 */
	private static List<String> splitArgs(final String text)
	{
		final List<String> args = new ArrayList<String>();
		final StringBuilder cur = new StringBuilder();
		int depth = 0;
		boolean inStr = false;
		for(int i = 0; i < text.length(); i++)
		{
			final char c = text.charAt(i);
			if(c == '"')
				inStr = !inStr;
			else
			if(!inStr && (c == '('))
				depth++;
			else
			if(!inStr && (c == ')'))
				depth--;
			else
			if(!inStr && (c == ',') && (depth == 0))
			{
				args.add(cur.toString());
				cur.setLength(0);
				continue;
			}
			cur.append(c);
		}
		args.add(cur.toString());
		return args;
	}

	/** 
	 * Strip '# ' immediate prefixes from macro-call arguments whose matching
	 * parameter is used immediately in the (parsed) macro body.
	 *
	 * @param code the candidate macro-call line
	 * @param macros the parsed macro definitions by lowercased name
	 */
	private static String stripHashImmediates(final String code, final Map<String, MacroInfo> macros)
	{
		final Matcher cm = Pattern.compile(
			"(?i)^(\\s*)((?:[A-Za-z_@][A-Za-z0-9_@$]*|[0-9]+\\$):)?\\s*"
			+ "([A-Za-z_][A-Za-z0-9_]*)\\s+(.*)$")
			.matcher(code);
		if(!cm.find())
			return code;
		final String mac = cm.group(3).toLowerCase();
		final MacroInfo info = macros.get(mac);
		if((info == null) || info.immediateParams.isEmpty())
			return code;
		final List<String> args = splitArgs(cm.group(4));
		boolean changed = false;
		for(final Integer idx : info.immediateParams)
		{
			if(idx < args.size())
			{
				final String a = args.get(idx).trim();
				if(a.startsWith("#"))
				{
					args.set(idx, a.substring(1).trim());
					changed = true;
				}
			}
		}
		if(!changed)
			return code;
		final StringBuilder sb = new StringBuilder();
		for(int i = 0; i < args.size(); i++)
		{
			if(i > 0)
				sb.append(", ");
			sb.append(args.get(i).trim());
		}
		return cm.group(1) + ((cm.group(2) != null) ? (cm.group(2) + "\t") : "")
			+ cm.group(3) + "\t" + sb.toString();
	}

	/** 
	 * Expand a macro call ("ldw A0,#$1234", "avw #8,A0", "cbi $c00f,#$40")
	 * into its parsed body: arguments are substituted for parameters
	 * (word-boundary, '#' stripped when the body uses the parameter
	 * immediately), private labels become per-expansion anonymous labels,
	 * and each body line is pushed through the standard rewrite so offset
	 * operators and numeric locals come out as as in module code.  Returns null
	 * when the line is not a call to a known macro.
	 *
	 * @param code the candidate macro-call line
	 * @param st the current conversion state
	 * @param macros the parsed macro definitions by lowercased name
	 */
	private static List<String> expandMacroCall(final String code, final State st,
		final Map<String, MacroInfo> macros)
	{
		final Matcher cm = Pattern.compile(
			"(?i)^(\\s*)((?:[A-Za-z_@][A-Za-z0-9_@$]*|[0-9]+\\$):)?\\s*"
			+ "([A-Za-z_][A-Za-z0-9_]*)\\s+(.*)$")
			.matcher(code);
		if(!cm.find())
			return null;
		final MacroInfo info = macros.get(cm.group(3).toLowerCase());
		if(info == null)
			return null;
		final List<String> args = splitArgs(cm.group(4));
		final List<String> subbed = new ArrayList<String>(info.body.size());
		for(String bl : info.body)
		{
			for(int pi = 0; pi < info.params.size(); pi++)
			{
				final String arg = (pi < args.size()) ? args.get(pi).trim() : "";
				final String subst = info.immediateParams.contains(Integer.valueOf(pi))
					? arg.replaceFirst("^#", "").trim()
					: arg;
				final String p = info.params.get(pi);
				bl = bl.replaceAll("(?<![A-Za-z0-9_])" + Pattern.quote(p) + "(?![A-Za-z0-9_])",
					Matcher.quoteReplacement(subst));
			}
			subbed.add(bl);
		}
		final List<String> body = anonMacroBody(subbed, false);
		final List<String> out = new ArrayList<String>();
		if((cm.group(2) != null) && (body.size() > 0))
			out.add(rewriteCode(cm.group(2), true, st));
		for(String l : body)
		{
			final Matcher dm = DIRECTIVE_PATTERN.matcher(l);
			if(dm.find())
			{
				final String d = dm.group(1);
				final String dlow = d.toLowerCase();
				if(dlow.equals(".if") || dlow.equals(".elseif"))
				{
					final String op = l.substring(dm.end()).trim();
					out.add(makeLine(l.substring(0, dm.start()), d,
						"(" + rewriteCode(op, false, st).trim() + ")", ""));
					continue;
				}
				if(dlow.equals(".else") || dlow.equals(".endif"))
				{
					out.add(l);
					continue;
				}
			}
			out.add(rewriteCode(l, true, st).replaceFirst("[\\s,]+$", ""));
		}
		return out;
	}

	// ---------------- link mode (--lnk) ----------------

	private static final class LnkRecord
	{
		final int num;
		final List<String> rels = new ArrayList<String>();
		String psect = null;
		LnkRecord(final int num)
		{
			this.num = num;
		}
	}

	private static final class LnkManifest
	{
		String appName = null;
		String structure = "SEQ";
		String headerRel = null;
		String psect = null;
		String ramsect = null;
		final List<LnkRecord> records = new ArrayList<LnkRecord>();
	}

	private static final class ModulePlan
	{
		final String rel;
		final String disp;
		final File doc;
		final List<Integer> records = new ArrayList<Integer>();
		ModulePlan(final String rel, final String disp, final File doc)
		{
			this.rel = rel;
			this.disp = disp;
			this.doc = doc;
		}
		void addRecord(final int num)
		{
			if(!records.contains(Integer.valueOf(num)))
				records.add(Integer.valueOf(num));
		}
	}

	private static LnkManifest parseLnk(final List<String> lines)
	{
		final LnkManifest m = new LnkManifest();
		LnkRecord cur = new LnkRecord(0);
		m.records.add(cur);
		for(final String raw : lines)
		{
			final String t = stripComment(raw).trim();
			if(t.length() == 0)
				continue;
			if(t.startsWith("."))
			{
				final Matcher dm = Pattern.compile("(?i)^\\.(\\S+)\\s*(.*)$").matcher(t);
				if(!dm.find())
					continue;
				final String d = dm.group(1).toLowerCase();
				final String arg = dm.group(2).trim();
				if(d.equals("output"))
					m.appName = arg;
				else
				if(d.equals("seq"))
					m.structure = "SEQ";
				else
				if(d.equals("vlir"))
					m.structure = "VLIR";
				else
				if(d.equals("cbm"))
					m.structure = "CBM";
				else
				if(d.equals("header"))
					m.headerRel = arg;
				else
				if(d.equals("psect"))
				{
					final String a = stripComment(arg).trim();
					// the first .psect is the program's run base; a .psect after
					// a .mod gives that overlay record's run base (a literal
					// address, or the program symbol VPRGbase).
					if(m.psect == null)
					{
						if(a.matches("\\$[0-9A-Fa-f]+") || a.matches("\\d+"))
							m.psect = a;
					}
					if((cur.num > 0) && (a.length() > 0))
						cur.psect = a;
				}
				else
				if(d.equals("ramsect"))
					m.ramsect = stripComment(arg).trim();
				else
				if(d.equals("mod"))
				{
					int num = 0;
					try
					{
						num = Integer.parseInt(arg.trim());
					}
					catch(final Exception e)
					{
						num = cur.num + 1;
					}
					cur = new LnkRecord(num);
					m.records.add(cur);
				}
				continue;
		}
		// any remaining non-directive line is a module reference; name matching
		// is by normalized doc basename and ignores the extension entirely
		cur.rels.add(t);
	}
	return m;
}

	/** 
	 * Normalize a linker module reference ("S/geoCBTScn.rel", "S/geoCBTScn.cvt",
	 * or bare "S/geoCBTScn") to a doc-search key: any trailing extension is
	 * ignored, and a leading directory letter becomes a prefix ("S/.." -> "s_..").
	 *
	 * @param rel the linker module reference
	 */
	private static String relKey(final String rel)
	{
		String r = rel;
		final int slash = r.indexOf('/');
		final int dot = r.lastIndexOf('.');
		if((dot > slash) && (dot > 0) && (r.length() > dot + 1))
			r = r.substring(0, dot);
		if(slash > 0)
		{
			final String dir = r.substring(0, slash).toLowerCase();
			if(dir.length() == 1)
				r = dir + "_" + r.substring(slash + 1);
			else
				r = r.substring(slash + 1);
		}
		return r.toLowerCase().replaceAll("[^a-z0-9]", "");
	}

	/** 
	 * Normalize a .cvt doc basename to the same search key.
	 *
	 * @param f the .cvt document file
	 */
	private static String docKey(final File f)
	{
		String n = f.getName();
		if(n.toLowerCase().endsWith(".cvt"))
			n = n.substring(0, n.length() - 4);
		return n.toLowerCase().replaceAll("[^a-z0-9]", "");
	}

	/**
	 * Resolve a rel name to a .cvt doc among the source dirs (first dir wins;
	 * exact key preferred, then a prefix/superset match).
	 *
	 * @param rel the linker module reference
	 * @param srcdirs the directories to search
	 */
	private static File resolveDoc(final String rel, final List<File> srcdirs)
	{
		final String key = relKey(rel);
		final List<File> docs = new ArrayList<File>();
		for(final File dir : srcdirs)
		{
			if((dir == null) || !dir.isDirectory())
				continue;
			final File[] fs = dir.listFiles();
			if(fs == null)
				continue;
			for(final File f : fs)
			{
				if(f.isFile() && f.getName().toLowerCase().endsWith(".cvt"))
					docs.add(f);
			}
		}
		File exact = null;
		File approx = null;
		for(final File f : docs)
		{
			final String dk = docKey(f);
			if(dk.equals(key))
			{
				exact = f;
				break;
			}
		}
		if(exact != null)
			return exact;
		for(final File f : docs)
		{
			final String dk = docKey(f);
			if((key.length() >= 5)
				&& (dk.startsWith(key) || key.startsWith(dk)))
			{
				approx = f;
				break;
			}
		}
		return approx;
	}

	private static File resolveLnk(final String rel, final List<File> srcdirs)
	{
		return resolveDoc(rel, srcdirs);
	}

	private static final class State
	{
		boolean macpackInjected = false;
		int pass1Depth = 0;
		boolean warnedInclude = false;
		boolean segmented = false;
		boolean warnedHeader = false;
		int dedupCount = 0;
		int macroDepth = 0;
		final Map<String, String> equates = new HashMap<String, String>();
		final Set<String> macroNames = new HashSet<String>();  // macro names (in-doc or parsed definitions doc), lowercased
		final Set<String> definedLabels = new HashSet<String>(); // 8-char truncated labels defined so far
		Integer curPicW = null;  // width of the most recent embedded clip-art image
		Integer curPicH = null;  // height of the most recent embedded clip-art image
	}

	/** Per-conversion options (used by link mode and single-file mode). */
	public static final class ConvertOptions
	{
		public boolean linkMode = false;
		public Integer overlay = null;                  // VLIR record number; null/0 = main CODE
		public Map<String, MacroInfo> macros = null;    // parsed .macro definitions, name-lowercased
		public final Map<String, String> includeMap = new HashMap<String, String>();
		/** 
		 * Single-file mode: bare .include of a GEOS system doc (normalized key)
		 * mapped to its converted equate/definition lines, already spliced in
		 * place of the .include at conversion time. */
		public final Map<String, List<String>> inlineIncludes = new HashMap<String, List<String>>();
	}

	public static final class ConvertResult
	{
	public final List<String>          lines              = new ArrayList<String>();
	public final List<String>          warnings           = new ArrayList<String>();
	public final Set<String>           notes              = new TreeSet<String>();
	public final Map<String, Integer>  unknownDirectives  = new TreeMap<String, Integer>();
	/** Fatal problems that make the output refuse to assemble; non-empty => main() aborts. */
	public final List<String>          errors             = new ArrayList<String>();
	public int                         macroExpansions    = 0;   // macro calls expanded inline
	/** Equate names that arrived via a spliced include (not the source's own
	 *  tokens); a same-named label defined in the source overrides them. */
	public final Set<String>           splicedEquates     = new HashSet<String>();
}

	public static void main(final String[] args)
	{
		if(args.length < 1)
		{
			usage();
			return;
		}
		if("--lnk".equals(args[0]) || "-lnk".equals(args[0]))
		{
			runLinkMode(args);
			return;
		}
		final List<File> srcdirs = environmentSourceDirs();
		File srcArg = null;
		File outArg = null;
		for(int i = 0; i < args.length; i++)
		{
			final String a = args[i];
			if(("--srcdir".equalsIgnoreCase(a) || "-I".equals(a)) && (i + 1 < args.length))
				srcdirs.add(new File(args[++i]));
			else
			if((a.length() > 1) && (a.charAt(0) == '-'))
			{
				System.err.println("Error: unknown option: " + a);
				usage();
				System.exit(-1);
			}
			else
			if(srcArg == null)
				srcArg = new File(a);
			else
			if(outArg == null)
				outArg = new File(a);
			else
			{
				System.err.println("Error: unexpected argument: " + a);
				usage();
				System.exit(-1);
			}
		}
		if(srcArg == null)
		{
			usage();
			return;
		}
		final File in = srcArg;
		if(!in.exists() || in.isDirectory())
		{
			System.err.println("Error: file not found: " + in.getPath());
			System.exit(-1);
		}
		final File inF = in;
		final File out = (outArg != null) ? outArg : outputFileFor(in);
		try
		{
			final GeoRWriter doc = new GeoRWriter(in, false);
			final List<String> sourceLines = extractLines(doc);
			final ConvertOptions opts = new ConvertOptions();
			// single-file self-containment: resolve .include docs so macros are
			// expanded and equates inlined instead of emitted as bare includes
			// (unresolvable includes still fail in verifyIncludes).
			resolveSystemIncludes(sourceLines, inF, srcdirs, opts);
			final ConvertResult res = convert(sourceLines, opts);
			// verify each emitted .include resolves next to the output (or source dir):
			// an unresolvable include means the file will not assemble, so fail loudly.
			verifyIncludes(res, out.getParentFile() != null ? out.getParentFile() : new File("."), inF);
			if(!res.errors.isEmpty())
			{
				System.err.println("Error: " + res.errors.size()
					+ " .include cannot be resolved next to '" + out.getName()
					+ "'; not writing output until the referenced files exist");
				for(final String e : res.errors)
					System.err.println("  missing include: " + e);
				System.exit(-1);
				return;
			}
			final List<String> outLines = new ArrayList<String>();
			outLines.add("; Converted from " + in.getName() + " by GeoAsmConv v" + D64Base.EMUTIL_VERSION + " (Emutil)");
			outLines.add("; GeoProgrammer (geoAssembler) -> ca65 (cc65) source.");
			outLines.add("; Assemble with: ca65 -o file.o file.s && ld65 -C your_config.cfg");
			if(!opts.inlineIncludes.isEmpty())
				outLines.add("; .include'd docs were inlined: output is self-contained.");
			else
			{
				outLines.add("; NOTE: Macro definitions and GEOS symbol includes must");
				outLines.add(";       be provided or ported separately for ca65.");
			}
			outLines.add("");
			outLines.addAll(res.lines);
			// geoAssembler permits FORWARD references to numeric locals ("blt 10$"
			// branching to a "10$:" defined below in the same routine); ca65 numeric
			// locals resolve only backward.  Bind each forward reference to a
			// synthetic named label emitted at the definition so the branch target
			// is preserved exactly.
			fixForwardNumericLocals(outLines);
			// A source module may redefine a GEOS equate name as its own label
			// (e.g. geoLoadStar redefines PrintBuf as its own .res buffer).  When a
			// label is defined in this file, a spliced-in equate of the same name
			// is real damage to the assembled symbol table; the app's label wins.
			{
				final Set<String> labDefs = new HashSet<String>();
				final Pattern labP = Pattern.compile("^([A-Za-z_][A-Za-z0-9_$]*):(?:\\s|$)");
				for(final String line : outLines)
				{
					final Matcher lm = labP.matcher(line);
					if(lm.find())
						labDefs.add(lm.group(1));
				}
				int dropped = 0;
				for(int i = 0; i < outLines.size(); i++)
				{
					final Matcher eq = Pattern.compile(
						"^\\s*([A-Za-z_][A-Za-z0-9_$]*)\\s*(:=|=)\\s+(.*)$").matcher(outLines.get(i));
					if(eq.find() && res.splicedEquates.contains(eq.group(1))
						&& labDefs.contains(eq.group(1)))
					{
						outLines.set(i, "; " + outLines.get(i).trim()
							+ " [GEOS equate overridden by this module's own label]");
						dropped++;
					}
				}
				if(dropped > 0)
					System.out.println("GeoAsmConv: equates overridden by module labels: " + dropped);
			}
			// single-file mode has no cross-object defs, so repair case-damaged
			// references from this file's own symbols (link mode does the same
			// per record / module).
			{
				final Set<String> defs = new HashSet<String>();
				collectSymbols(outLines, defs, new HashSet<String>());
				final int repairedCase = repairCaseLines(outLines, defs);
				if(repairedCase > 0)
					System.out.println("GeoAsmConv: case-repair: fixed " + repairedCase
						+ " case-damaged symbol reference(s)");
			}
			final FileOutputStream fo = new FileOutputStream(out);
			try
			{
				for(final String line : outLines)
				{
					fo.write(line.getBytes("ISO-8859-1"));
					fo.write('\n');
				}
			}
			finally
			{
				try
				{
					fo.close();
				}
				catch(final IOException e)
				{
				}
			}
			System.out.println("GeoAsmConv: converted " + sourceLines.size() + " source line(s) from "
				+ in.getAbsolutePath() + " -> " + out.getAbsolutePath());
			if(!opts.inlineIncludes.isEmpty())
				System.out.println("GeoAsmConv: inlined include doc(s): "
					+ joinStringsComma(new ArrayList<String>(opts.inlineIncludes.keySet())));
			if(res.macroExpansions > 0)
				System.out.println("GeoAsmConv: expanded " + res.macroExpansions
					+ " macro call(s) inline");
			if(!res.warnings.isEmpty() || !res.unknownDirectives.isEmpty() || !res.notes.isEmpty())
			{
				for(final String n : res.notes)
					System.err.println("note: " + n);
				for(final String w : res.warnings)
					System.err.println("warning: " + w);
				if(!res.unknownDirectives.isEmpty())
				{
					for(final Map.Entry<String, Integer> e : res.unknownDirectives.entrySet())
						System.err.println("warning: " + e.getValue() + "x '" + e.getKey()
							+ "' - unrecognized directive; commented out");
				}
			}
			else
				System.out.println("No conversion warnings.");
		}
		catch(final Exception e)
		{
			System.err.println("Error: " + e.getMessage());
			e.printStackTrace();
			System.exit(-1);
		}
	}

	private static File outputFileFor(final File in)
	{
		String name = in.getName();
		final int dot = name.lastIndexOf('.');
		if((dot > 0) && (dot < name.length() - 1))
			name = name.substring(0, dot) + ".s";
		else
			name = name + ".s";
		return new File(in.getParentFile(), name);
	}

	/** 
	 * Verify every emitted .include resolves to an existing file.  ca65 refuses
	 * to assemble when an include path is missing, so GeoAsmConv fails loudly here
	 * rather than emitting a file that will not build.  Searches the output dir,
	 * then the source doc's directory, then the current working directory; a bare
	 * name (e.g. "MYDEFS") is matched against either case or an explicit path.
	 *
	 * @param res the conversion result whose includes are checked
	 * @param outDir the output directory
	 * @param inF the source document file
	 */
	private static void verifyIncludes(final ConvertResult res, final File outDir, final File inF)
	{
		final File srcDir = (inF != null) ? inF.getParentFile() : new File(".");
		final Set<File> candidates = new LinkedHashSet<File>();
		candidates.add(outDir);
		candidates.add(srcDir);
		candidates.add(new File("."));
		for(final String line : res.lines)
		{
			final Matcher im = Pattern.compile("(?i)^\\s*\\.include\\s+\"([^\"]+)\"").matcher(line);
			if(!im.find())
				continue;
			final String inc = im.group(1).trim();
			boolean found = false;
			for(final File dir : candidates)
			{
				File cand = new File(dir, inc);
				if(cand.isFile())
				{
					found = true;
					break;
				}
				cand = new File(dir, stripQuoteName(inc));
				if(cand.isFile())
				{
					found = true;
					break;
				}
			}
			if(!found)
				res.errors.add("cannot locate .include target '" + inc + "' in: "
					+ joinPaths(candidates));
		}
	}

	/** 
	 * Strip surrounding quotes from an include path if present.
	 *
	 * @param s the include path
	 */
	private static String stripQuoteName(final String s)
	{
		String r = s.trim();
		if(r.length() >= 2 && r.charAt(0) == '"' && r.charAt(r.length() - 1) == '"')
			r = r.substring(1, r.length() - 1);
		return r;
	}

	private static String joinPaths(final Set<File> dirs)
	{
		final StringBuilder sb = new StringBuilder();
		boolean first = true;
		for(final File d : dirs)
		{
			if(!first)
				sb.append(", ");
			first = false;
			sb.append(d.getAbsolutePath());
		}
		return sb.toString();
	}

	/** 
	 * Resolve a rel name to a .cvt doc among the source dirs by exact key
	 * only (first dir wins).  Used for sibling-module includes, where the
	 * loose prefix/superset matching of resolveDoc() could pick the wrong file.
	 *
	 * @param rel the linker module reference
	 * @param srcdirs the directories to search
	 */
	private static File resolveDocExact(final String rel, final List<File> srcdirs)
	{
		final String key = relKey(rel);
		for(final File dir : srcdirs)
		{
			if((dir == null) || !dir.isDirectory())
				continue;
			final File[] fs = dir.listFiles();
			if(fs == null)
				continue;
			for(final File f : fs)
			{
				if(f.isFile() && f.getName().toLowerCase().endsWith(".cvt")
					&& docKey(f).equals(key))
					return f;
			}
		}
		return null;
	}

	/** 
	 * Single-file mode: resolve each bare .include to its .cvt in the source
	 * document's directory or one of the supplied search directories (--srcdir
	 * / GEOS_SRCDIR).  The doc's .macro definitions are parsed into
	 * {@code opts.macros} (so macro calls get expanded inline); a definitions
	 * doc (no code section) has its converted equate/definition lines spliced in
	 * place of the .include, a code doc its whole converted body.  Unresolvable
	 * includes are left untouched: they keep the old ".include X.inc" behavior
	 * and are flagged by verifyIncludes() so nothing silently unassembleable is
	 * written.
	 *
	 * @param sourceLines the source lines to scan for bare .include directives
	 * @param inF the source document file, used to locate the source directory
	 * @param extraDirs additional directories to search (may be null)
	 * @param opts the conversion options to populate with parsed macros and inlined includes
	 */
	private static void resolveSystemIncludes(final List<String> sourceLines, final File inF,
		final List<File> extraDirs, final ConvertOptions opts) throws Exception
	{
		final List<File> srcdirs = new ArrayList<File>();
		if(inF != null)
			srcdirs.add(inF.getParentFile());
		if(extraDirs != null)
			for(final File d : extraDirs)
				if((d != null) && !srcdirs.contains(d))
					srcdirs.add(d);
		final Map<String, MacroInfo> macros = new LinkedHashMap<String, MacroInfo>();
		macros.putAll(parseMacros(sourceLines));   // any macros the source defines itself
		// Discover the whole include graph (definitions docs plus sibling
		// modules) with a breadth-first queue, then process definitions docs
		// first so their equates/macros are inlined before any sibling that
		// depends on them.
		final List<String> work = new ArrayList<String>();
		final List<String[]> system = new ArrayList<String[]>();
		final List<String[]> sibling = new ArrayList<String[]>();
		for(final String line : sourceLines)
		{
			final String t = stripComment(line).trim();
			if(!t.toLowerCase().startsWith(".include"))
				continue;
			final Matcher im = Pattern.compile("(?i)^\\.include\\s*(.*)$").matcher(t);
			final String operand = im.find() ? im.group(1).trim() : "";
			if(operand.length() == 0 || operand.charAt(0) == '"')
				continue;
			work.add(operand);
		}
		final Set<String> seen = new HashSet<String>();
		for(int wi = 0; wi < work.size(); wi++)
		{
			final String operand = work.get(wi);
			final String key = operand.toLowerCase().replaceAll("[^a-z0-9]", "");
			if(!seen.add(key))
				continue;
			final File doc = resolveInclude(operand, srcdirs);
			if(doc == null)
				continue;   // verifyIncludes() will flag the .include later
			final GeoRWriter gsd = new GeoRWriter(doc, false);
			final List<String> docLines = extractLines(gsd);
			final boolean isSystem = isDefinitionsDoc(docLines);
			// nested bare includes resolve against this doc's own parent dir first
			for(final String line : docLines)
			{
				final String t2 = stripComment(line).trim();
				if(!t2.toLowerCase().startsWith(".include"))
					continue;
				final Matcher im2 = Pattern.compile("(?i)^\\.include\\s*(.*)$").matcher(t2);
				final String op2 = im2.find() ? im2.group(1).trim() : "";
				if(op2.length() == 0 || op2.charAt(0) == '"')
					continue;
				work.add(op2);
			}
			(isSystem ? system : sibling).add(new String[] { operand, key, doc.getPath() });
		}
		// phase 1: definitions docs (equates + .macro definitions, no code).
		// Calls are expanded inline, so .macro/.endmacro bodies are never
		// emitted; only equates, directives, and comments survive.
		for(final String[] inc : system)
		{
			//final String operand = inc[0];
			final String key = inc[1];
			final GeoRWriter gsd = new GeoRWriter(new File(inc[2]), false);
			final List<String> docLines = extractLines(gsd);
			macros.putAll(parseMacros(docLines));
			final ConvertResult conv = convert(docLines, new ConvertOptions());
			final List<String> inline = new ArrayList<String>();
			boolean inMacro = false;
			for(final String cl : conv.lines)
			{
				final String ct = cl.trim();
				if(ct.startsWith(".macro"))
				{
					inMacro = true;
					continue;
				}
				if(ct.startsWith(".endmacro") || ct.startsWith(".endm"))
				{
					inMacro = false;
					continue;
				}
				if(inMacro)
					continue;
				if(ct.length() == 0 || ct.startsWith(";") || ct.startsWith("."))
				{
					inline.add(cl);
					continue;
				}
				if(ct.matches("[A-Za-z_][A-Za-z0-9_]*\\s*(:=|=).*"))
				{
					inline.add(cl);
					continue;
				}
				// stray top-level fragment (page overlap): drop
			}
			opts.inlineIncludes.put(key, inline);
		}
		// phase 3: sibling modules -- whole code modules textually .include'd
		// by the source (MODem128 includes MODmodem, which includes its defs doc).
		// They are converted with the accumulated macros so macro calls inside
		// them expand, and their own .macro definitions join the set; the inline
		// keeps all instruction/equate/directive lines (macro bodies are dropped).
		// Siblings are ordered parents-before-children so nested includes splice.
		for(final String[] inc : sibling)
		{
			//final String operand = inc[0];
			final String key = inc[1];
			final GeoRWriter gsd = new GeoRWriter(new File(inc[2]), false);
			final List<String> docLines = extractLines(gsd);
			macros.putAll(parseMacros(docLines));
			final ConvertOptions o2 = new ConvertOptions();
			o2.macros = macros;
			o2.inlineIncludes.putAll(opts.inlineIncludes);
			final ConvertResult conv = convert(docLines, o2);
			opts.inlineIncludes.putAll(o2.inlineIncludes);
			final List<String> inline = new ArrayList<String>();
			boolean inMacro = false;
			for(final String cl : conv.lines)
			{
				final String ct = cl.trim();
				if(ct.startsWith(".macro"))
				{
					inMacro = true;
					continue;
				}
				if(ct.startsWith(".endmacro") || ct.startsWith(".endm"))
				{
					inMacro = false;
					continue;
				}
				if(inMacro)
					continue;
				inline.add(cl);
			}
			opts.inlineIncludes.put(key, inline);
		}
		opts.macros = macros;
	}

	private static void usage()
	{
		System.out.println("GeoAsmConv v" + D64Base.EMUTIL_VERSION + " (c)2026-" + D64Base.EMUTIL_AUTHOR);
		System.out.println("");
		System.out.println("USAGE: GeoAsmConv <source.cvt> [output.s] [--srcdir dir]...");
		System.out.println("       GeoAsmConv --lnk <manifest.lnk.cvt> [--srcdir dir]...");
		System.out.println("                  [--outdir dir] [--overlaysize bytes] [--cfg file]");
		System.out.println("");
		System.out.println("Converts a GeoProgrammer (geoAssembler) assembly source document");
		System.out.println("(.CVT GeoWrite file) into cc65 (ca65) assembler source.");
		System.out.println("If [output.s] is omitted the source name is used with a .s extension.");
		System.out.println("");
		System.out.println("--srcdir  Add a directory searched for system includes and, in link");
		System.out.println("          mode, module/resource source documents.  Repeatable.  The");
		System.out.println("          GEOS_SRCDIR environment variable supplies default dirs");
		System.out.println("          separated by the platform path separator.");
		System.out.println("--cfg     Use the given cc65 linker config instead of the located");
		System.out.println("          geos-cbm.cfg (also: CC65_CFG, or CC65_HOME).");
		System.out.println("");
		System.out.println("Notes:");
		System.out.println("  - project macros (ldw, ldb, mvb, etc.) are parsed from the");
		System.out.println("    source and its .include'd docs and expanded inline; the");
		System.out.println("    docs are located via --srcdir/GEOS_SRCDIR.");
		System.out.println("  - .header/.endh (GEOS app header) block is preserved but requires");
		System.out.println("    manual attention for ca65 build (cc65's geos lib provides the");
		System.out.println("    equivalent gapp mechanism).");
	}

	public static List<String> extractLines(final GeoRWriter doc)
	{
		final StringBuilder str = new StringBuilder();
		// Each page's EOP (end-of-page) marker acts as a line terminator even
		// when the page's last line has no trailing CR, so ensure a newline at
		// every page boundary to keep source lines from merging across pages.
		final int numPages = doc.getNumPages();
		final int[] pageBase = new int[numPages];
		int lineCount = 0;
		for(int p = 0; p < numPages; p++)
		{
			pageBase[p] = lineCount;
			final int before = str.length();
			str.append(doc.getPage(p));
			if(str.length() > 0 && str.charAt(str.length() - 1) != '\n')
				str.append('\n');
			for(int k = before; k < str.length(); k++)
				if(str.charAt(k) == '\n')
					lineCount++;
		}
		final List<String> lines = new ArrayList<String>();
		final String[] parts = str.toString().split("\n", -1);
		int total = parts.length;
		if((total > 0) && (parts[total - 1].length() == 0))
			total--;
		// Group each page's embedded pictures by the global line index they
		// precede, so the picture bytes are emitted at their in-document spot.
		final Map<Integer, List<GeoRWriter.ClipRef>> picsByLine =
			new HashMap<Integer, List<GeoRWriter.ClipRef>>();
		for(int p = 0; p < numPages; p++)
		{
			for(final GeoRWriter.ClipRef pic : doc.getPagePictures(p))
			{
				final Integer idx = Integer.valueOf(pageBase[p] + pic.lineIndex);
				List<GeoRWriter.ClipRef> lst = picsByLine.get(idx);
				if(lst == null)
				{
					lst = new ArrayList<GeoRWriter.ClipRef>();
					picsByLine.put(idx, lst);
				}
				lst.add(pic);
			}
		}
		for(int i = 0; i <= total; i++)
		{
			final List<GeoRWriter.ClipRef> pics = picsByLine.get(Integer.valueOf(i));
			if(pics != null)
				for(final GeoRWriter.ClipRef pic : pics)
					appendClipArt(lines, doc, pic);
			if(i < total)
				lines.add(parts[i]);
		}
		return lines;
	}

	/**
	 * Emit an embedded clip-art image as a cc65 hex byte stream, preceded by a
	 * marker line that binds "picW"/"picH" to the image's pixel dimensions.
	 * The scrap opens with a 3-byte width/height header; the assembled source
	 * carries only the pixel payload (dimensions ride in picW/picH).
	 *
	 * @param out the output lines to append to
	 * @param doc the document holding the clip-art record
	 * @param pic the picture reference to emit
	 */
	private static void appendClipArt(final List<String> out, final GeoRWriter doc,
		final GeoRWriter.ClipRef pic)
	{
		out.add(";@CLIPART " + pic.width + " " + pic.height
			+ "\t; geoWrite clip art, VLIR record " + pic.record);
		final byte[] scrap = doc.getRecordData(pic.record);
		if((scrap == null)||(scrap.length <= 3))
		{
			out.add("; [missing clip-art record " + pic.record + "]");
			return;
		}
		final byte[] data = new byte[scrap.length - 3];
		System.arraycopy(scrap, 3, data, 0, data.length);
		for(int i = 0; i < data.length; i += 16)
		{
			final StringBuilder sb = new StringBuilder("\t.byte\t");
			final int end = Math.min(i + 16, data.length);
			for(int j = i; j < end; j++)
			{
				if(j > i)
					sb.append(',');
				sb.append('$').append(String.format("%02x", Integer.valueOf(data[j] & 0xff)));
			}
			out.add(sb.toString());
		}
	}

	public static ConvertResult convert(final List<String> sourceLines)
	{
		return convert(sourceLines, new ConvertOptions());
	}

	public static ConvertResult convert(final List<String> sourceLines, final ConvertOptions options)
	{
		final ConvertResult res = new ConvertResult();
		final State st = new State();
		// macro names: defined in this doc, or supplied (link mode) from the
		// parsed definitions doc(s).
		st.macroNames.addAll(parseMacros(sourceLines).keySet());
		if(options.macros != null)
			st.macroNames.addAll(options.macros.keySet());
		for(final String line : sourceLines)
			translateLine(line, st, res, options);
		if(st.macroDepth != 0)
		{
			res.warnings.add("unbalanced .macro/.endmacro: "
				+ ((st.macroDepth > 0) ? st.macroDepth + " unterminated macro(s)"
				: (-st.macroDepth) + " stray .endmacro(s) (duplicated page content in source document?)"));
		}
		if(st.pass1Depth != 0)
			res.warnings.add("unterminated .if Pass1 block (source may be incomplete)");
		if(st.dedupCount > 0)
		{
			res.warnings.add("removed " + st.dedupCount
				+ " identical duplicate equate line(s) (page-overlap artifact in source document)");
		}
		// geoAssembler assembled each module as its own object, so the section
		// state does NOT carry over from the previous module: every module
		// starts in its record's default segment.  Otherwise a module whose
		// first directive is a later ".ramsect" (e.g. geoBrowser's MODdaAB)
		// inherits the previous module's BSS and its code is dropped from the
		// loaded image.
		final boolean isOverlay = (options.overlay != null) && (options.overlay.intValue() > 0);
		res.lines.add(0, isOverlay
			? (".segment\t\"OVERLAY" + options.overlay + "\"")
			: ".segment\t\"CODE\"");
		return res;
	}

	private static void translateLine(final String line, final State st, final ConvertResult res,
		final ConvertOptions options)
	{
		// clip-art marker emitted by extractLines: records the pixel dimensions
		// of the picture that follows, so that source "picW"/"picH" references
		// resolve to the immediately preceding image's size.
		if(line.startsWith(";@CLIPART "))
		{
			final String[] cparts = line.substring(10).trim().split("\\s+");
			if(cparts.length >= 2)
			{
				try
				{
					st.curPicW = Integer.valueOf(cparts[0]);
					st.curPicH = Integer.valueOf(cparts[1]);
				}
				catch(final NumberFormatException e)
				{
				}
			}
			return;
		}
		final int ci = findCommentStart(line);
		final String codePart = (ci < 0) ? line : line.substring(0, ci);
		final String comment  = (ci < 0) ? "" : line.substring(ci);
		final String trimmed  = codePart.trim();

		if(trimmed.length() == 0)
		{
			res.lines.add(line);
			return;
		}

		// fused source-doc lines: "ArowTyp: .res 1: .byte $18,..." carries two
		// statements on one line (page damage collapsed a line break).  Split them
		// and re-dispatch so each statement is translated normally; the trailing
		// comment rides on the last part.
		final List<String> fused = splitFusedStatements(codePart, st);
		if(fused != null)
		{
			for(int fi = 0; fi < fused.size(); fi++)
			{
				final String piece = fused.get(fi);
				final boolean last = (fi == fused.size() - 1);
				translateLine(piece + (last && (comment.length() > 0) ? "\t" + comment : ""),
					st, res, options);
			}
			return;
		}

		// duplicate label detection: page damage can duplicate whole blocks of
		// a source doc, and the repeat definition would be a ca65 error.  The
		// first occurrence wins; the second is commented out with a warning.
		// (Macro bodies are exempt: their labels are per-expansion.)
		if((st.macroDepth == 0) && isLabelDef(trimmed, st)
			&& trimmed.matches("^[A-Za-z_][A-Za-z0-9_$]*:.*"))
		{
			final Matcher lbm = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*):").matcher(trimmed);
			if(lbm.find())
			{
				String lname = lbm.group(1);
				if(lname.length() > 8)   // geoAssembler 8-significant-char rule
					lname = lname.substring(0, 8);
				if(!st.definedLabels.add(lname) || st.equates.containsKey(lname))
				{
					res.warnings.add("duplicate label '" + lbm.group(1)
						+ "' (page-damage duplication or equate clash); second occurrence removed");
					res.lines.add("; [" + trimmed.trim() + "] removed; duplicate label (page damage)"
						+ ((comment.length() > 0) ? "\t" + comment : ""));
					return;
				}
			}
		}

		// pass-1 block state: we are inside an outer .if Pass1 ... .endif.
		// The wrappers are dropped; the body falls through to normal
		// directive translation so includes/equates still get rewritten.
		if(st.pass1Depth > 0)
		{
			final Matcher dm = DIRECTIVE_PATTERN.matcher(trimmed);
			final String d = dm.find() ? dm.group(1) : null;
			if(d != null && d.equalsIgnoreCase(".if"))
			{
				st.pass1Depth++;
				if(PASS1_PATTERN.matcher(trimmed.substring(dm.end())).find())
					res.lines.add(""); // nested pass1 guard: drop it
				else
					res.lines.add(rewriteCode(codePart, false, st) + comment);
				return;
			}
			if(d != null && d.equalsIgnoreCase(".endif"))
			{
				st.pass1Depth--;
				if(st.pass1Depth == 0)
					res.lines.add(""); // closing the outer wrapper: drop it
				else
					res.lines.add(rewriteCode(codePart, false, st) + comment);
				return;
			}
			if(d != null && (d.equalsIgnoreCase(".else") || d.equalsIgnoreCase(".elseif")))
			{
				if(st.pass1Depth == 1)
					res.lines.add(""); // outer wrapper's else: drop
				else
					res.lines.add(rewriteCode(codePart, false, st) + comment);
				return;
			}
			// body of pass1 block: continue into the normal directive dispatch
		}

		// detect a directive token in the code
		final Matcher m = DIRECTIVE_PATTERN.matcher(codePart);
		if(!m.find())
		{
			// no directive: plain instruction, label, or comment line
			if(!handleEquateLine(trimmed, comment, st, res, options))
			{
				final String fixed = sanitizeFragment(codePart, trimmed, res, st);
				if(fixed == null)
				{
					res.lines.add("; [" + trimmed.trim() + "] removed; source-doc fragment"
						+ ((comment.length() > 0) ? "\t" + comment : ""));
					return;
				}
				// single-file mode: expand macro calls inline (avw, ldw, cbi, ...)
				// from the resolved definitions docs.  No .scope wrapper, so
				// symbolic ".if" operands stay evaluable; anonymous labels isolate
				// each expansion.  The call's own comment rides on the first line.
				if(!options.linkMode && (options.macros != null) && (st.macroDepth == 0))
				{
					final List<String> exp = expandMacroCall(fixed, st, options.macros);
					if(exp != null)
					{
						res.macroExpansions++;
						res.lines.add(exp.get(0) + comment);
						for(int xi = 1; xi < exp.size(); xi++)
							res.lines.add(exp.get(xi));
						return;
					}
				}
				// rewrite operands BEFORE stripping macro-arg immediates: while
				// the '#' is still present, "$NN" stays a hex literal instead of
				// being taken for a numeric-local reference ("avw #$01" case).
				String code = rewriteCode(fixed, true, st);
				if(options.linkMode && (options.macros != null))
					code = stripHashImmediates(code, options.macros);
				// geoAssembler tolerated a trailing comma on instruction lines
				res.lines.add(code.replaceFirst("[\\s,]+$", "") + comment);
			}
			return;
		}

		final String before = rewriteCode(codePart.substring(0, m.start()), false, st);
		final String dname = m.group(1);
		final String dnameRaw = dname.toLowerCase();
		final String operandRaw = codePart.substring(m.end());
		final String operandTrimmed = operandRaw.trim();

		// ---- .if Pass1 guard (wrapper removal) ----
		if(dnameRaw.equals(".if") && PASS1_PATTERN.matcher(operandTrimmed).find())
		{
			final String low = operandTrimmed.toLowerCase().trim();
			if(low.startsWith("not") || low.indexOf('!') >= 0)
			{
				res.warnings.add("'." + dname + " " + operandTrimmed
					+ "' (negated Pass1) kept verbatim; ca65 has no pass-1 concept");
				res.lines.add(rewriteCode(codePart, false, st) + comment);
			}
			else
			{
				st.pass1Depth = 1; // body will be emitted by lines above
				res.lines.add(""); // drop the .if Pass1 line itself
			}
			return;
		}

		// rewrite operand (generic: [ ] < >, N$ cheap locals, == equate)
		final String rewrittenOperand = rewriteCode(operandTrimmed, !isConditionalDirective(dnameRaw), st);
		final String rewrittenCode    = rewriteCode(codePart, !isConditionalDirective(dnameRaw), st);

		// empty-operand data directives are page-damage trailing lines
		// (geoAssembler tolerated them; ca65 rejects them).  A label prefix is
		// kept -- the damage is the empty operand, not the symbol.
		if((dnameRaw.equals(".byte") || dnameRaw.equals(".word") || dnameRaw.equals(".dbyt")
			|| dnameRaw.equals(".dword") || dnameRaw.equals(".addr") || dnameRaw.equals(".faraddr")
			|| dnameRaw.equals(".res") || dnameRaw.equals(".block")) && (operandTrimmed.length() == 0)
			&& (before.trim().length() == 0))
		{
			res.warnings.add("'" + trimmed.trim()
				+ "' has an empty operand (page-damage trailing line); commented out");
			res.lines.add("; [" + trimmed.trim() + "] removed; empty data operand (page damage)"
				+ ((comment.length() > 0) ? "\t" + comment : ""));
			return;
		}

		// ---- known directive translations ----

		// sections
		if(dnameRaw.equals(".psect"))
		{
			if(!st.segmented)
			{
				res.notes.add("remap CODE/BSS/ZEROPAGE segments in your ld65 .cfg memory layout");
				st.segmented = true;
			}
			final String note = (operandTrimmed.length() > 0)
				? " ; (was .psect " + operandTrimmed + ")"
				: "";
			final boolean isOverlay = (options.overlay != null) && (options.overlay.intValue() > 0);
			final String seg = isOverlay ? ("OVERLAY" + options.overlay) : "CODE";
			final String ovNote = isOverlay
				? (" ; VLIR record " + options.overlay + " (geoLinker .mod)")
				: "";
			res.lines.add(makeLine(before, ".segment", "\"" + seg + "\"" + note + ovNote, comment));
			return;
		}
		if(dnameRaw.equals(".ramsect"))
		{
			if(!st.segmented)
			{
				res.notes.add("remap CODE/BSS/ZEROPAGE segments in your ld65 .cfg memory layout");
				st.segmented = true;
			}
			// geoAssembler gave each ".mod" overlay its own RAM area, placed
			// right after that overlay's code at VPRGbase (end of the main
			// program's RAM).  A single shared BSS would pool every overlay's
			// variables into the main record, so name it per record.
			final boolean ramOverlay = (options.overlay != null) && (options.overlay.intValue() > 0);
			res.lines.add(makeLine(before, ".segment",
				ramOverlay ? ("\"OVLRAM" + options.overlay + "\"") : "\"BSS\"", comment));
			return;
		}
		if(dnameRaw.equals(".zsect"))
		{
			if(!st.segmented)
			{
				res.notes.add("remap CODE/BSS/ZEROPAGE segments in your ld65 .cfg memory layout");
				st.segmented = true;
			}
			res.lines.add(makeLine(before, ".segment",
				"\"ZEROPAGE\": zeropage", comment));
			return;
		}

		// .block -> .res
		if(dnameRaw.equals(".block"))
		{
			final String blkOperand = rewriteCode(operandTrimmed, false, false, st);
			res.lines.add(stripDataJunk(makeLine(before, ".res", blkOperand, comment)));
			return;
		}

		// .text -> scrcode (screen codes via macpack cbm)
		if(dnameRaw.equals(".text"))
		{
			if((rewrittenOperand.length() > 0) && (rewrittenOperand.charAt(0) == '"'))
			{
				final String[] parts = rewrittenOperand.split(",", -1);
				boolean allStrings = true;
				for(final String p : parts)
				{
					final String t = p.trim();
					if((t.length() > 0) && (t.charAt(0) != '"'))
					{
						allStrings = false;
						break;
					}
				}
				if(allStrings)
				{
					if(!st.macpackInjected)
					{
						res.lines.add(".macpack cbm\t; screen-code string support (GeoAsmConv)");
						st.macpackInjected = true;
					}
					res.lines.add(makeLine(before, "scrcode", rewrittenOperand,
						comment + (comment.length() > 0 ? " " : "") + "; screen codes"));
					return;
				}
			}
			res.warnings.add("'.text' with non-string operands kept verbatim: '"
				+ operandTrimmed + "'");
			res.lines.add(rewrittenCode + comment);
			return;
		}

		// .pet -> .byte (charmap required, noted)
		if(dnameRaw.equals(".pet"))
		{
			res.lines.add(makeLine(before, ".byte", rewrittenOperand,
				comment + (comment.length() > 0 ? " " : "") + "; .pet PETSCII - needs charmap"));
			return;
		}

		// .include: quote bare names, add .inc extension
		if(dnameRaw.equals(".include"))
		{
			if(operandTrimmed.length() == 0 || operandTrimmed.charAt(0) == '"')
			{
				res.lines.add(rewrittenCode + comment);
			}
			else
			{
				final String t = operandTrimmed.trim();
				if(options.linkMode)
				{
					final String key = t.toLowerCase().replaceAll("[^a-z0-9]", "");
					if(options.includeMap.containsKey(key))
					{
						res.lines.add(makeLine(before, ".include",
							"\"" + options.includeMap.get(key) + "\"", comment));
					}
					else
					{
						res.lines.add(makeLine(before, ".include", "\"" + t + ".inc\"",
							comment + (comment.length() > 0 ? " " : "")
							+ " ; no definitions doc for this include"));
						res.warnings.add("link-mode: no source doc for include '"
							+ t + "' (emitted .inc reference; will not assemble)");
					}
				}
				else
				{
					final String key = t.toLowerCase().replaceAll("[^a-z0-9]", "");
					if(options.inlineIncludes.containsKey(key))
					{
						final List<String> inline = options.inlineIncludes.get(key);
						res.lines.add("; '" + t + "' inlined by GeoAsmConv (" + key
							+ "); GEOS symbols/macros are self-contained in this file" + comment);
						for(final String il : inline)
						{
							final Matcher iq = Pattern.compile(
								"^([A-Za-z_][A-Za-z0-9_$]*)\\s*(:=|=)\\s+").matcher(il);
							if(iq.find())
								res.splicedEquates.add(iq.group(1));
						}
						// include-guard so a doc inlined through several paths
						// (a definitions doc directly and via a sibling) is not re-emitted
						res.lines.add(".ifndef\t__GXINL_" + key + "__");
						res.lines.add("__GXINL_" + key + "__ = 1");
						res.lines.addAll(inline);
						res.lines.add(".endif\t; __GXINL_" + key + "__");
						return;
					}
					final String name = (t.indexOf('.') >= 0) ? t : (t + ".inc");
					res.lines.add(makeLine(before, ".include", "\"" + name + "\"",
						comment + (comment.length() > 0 ? " " : "")
						+ "; GEOS symbols/must be supplied separately"));
					if(!st.warnedInclude)
					{
						res.notes.add("unresolved GEOS include(s) have no auto-port to ca65");
						st.warnedInclude = true;
					}
				}
			}
			return;
		}

		// .endm -> .endmacro
		if(dnameRaw.equals(".endm"))
		{
			st.macroDepth--;
			res.lines.add(makeLine(before, ".endmacro", "", comment));
			return;
		}
		if(dnameRaw.equals(".endmacro"))
		{
			st.macroDepth--;
			res.lines.add(rewrittenCode + comment);
			return;
		}

		// .macro: ca65 wants ".macro NAME p1, p2" (no comma after the name)
		if(dnameRaw.equals(".macro"))
		{
			final Matcher mm = Pattern.compile(
				"(?i)\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*,?\\s*(.*)")
				.matcher(operandTrimmed);
			if(mm.find())
			{
				final String macName = mm.group(1);
				final StringBuilder macArgs = new StringBuilder();
				for(final String p : mm.group(2).split("\\s*,\\s*"))
				{
					if(p.length() == 0)
						continue;
					final String pLow = p.toLowerCase();
					if(pLow.equals("a") || pLow.equals("x") || pLow.equals("y"))
						res.warnings.add("macro '" + macName + "': ca65 rejects '" + p
							+ "' as a parameter name (CPU register pseudo-variable); rename it");
					if(macArgs.length() > 0)
						macArgs.append(", ");
					macArgs.append(p);
				}
				final String macOp = (macArgs.length() > 0)
					? (macName + " " + macArgs.toString())
					: macName;
				st.macroDepth++;
				res.lines.add(makeLine(before, ".macro", macOp, comment));
			}
			else
			{
				res.lines.add(rewrittenCode + comment);
			}
			return;
		}

		// wider data directives
		if(dnameRaw.equals(".wordr"))
		{
			res.lines.add(makeLine(before, ".dbyt",
				rewriteCode(operandTrimmed, true, false, st), comment));
			return;
		}
		if(dnameRaw.equals(".lword"))
		{
			res.lines.add(makeLine(before, ".dword",
				rewriteCode(operandTrimmed, true, false, st), comment));
			return;
		}
		if(dnameRaw.equals(".long"))
		{
			res.lines.add(makeLine(before, ".faraddr",
				rewriteCode(operandTrimmed, true, false, st), comment));
			return;
		}

		// cpu mode
		if(dnameRaw.equals(".6502"))
		{
			res.lines.add(makeLine(before, ".P02", rewrittenOperand, comment));
			return;
		}
		if(dnameRaw.equals(".65c02"))
		{
			res.lines.add(makeLine(before, ".PC02", rewrittenOperand, comment));
			return;
		}
		if(dnameRaw.equals(".65816"))
		{
			res.lines.add(makeLine(before, ".P816", rewrittenOperand, comment));
			return;
		}

		// GEOS-only directives: drop or emit as comment
		if(dnameRaw.equals(".noeqin") || dnameRaw.equals(".eqin"))
		{
			res.lines.add("; [" + trimmed.trim()
				+ "] removed; ca65 has no pass-1 equate suppression"
				+ (comment.length() > 0 ? "\t" + comment : ""));
			return;
		}
		if(dnameRaw.equals(".noglbl") || dnameRaw.equals(".glbl"))
		{
			res.lines.add("; [" + trimmed.trim()
				+ "] removed; ca65 manages imports/exports explicitly"
				+ (comment.length() > 0 ? "\t" + comment : ""));
			return;
		}
		if(dnameRaw.equals(".header") || dnameRaw.equals(".endh"))
		{
			if(!st.warnedHeader)
			{
				res.notes.add("GEOS .header/.endh block preserved; build a ca65 gapp or "
					+ "configure header bytes in your ld65 .cfg manually");
				st.warnedHeader = true;
			}
			final String tag = dnameRaw.equals(".header")
				? "; ---- GEOS application header start (treat as raw bytes for ca65) ----"
				: "; ---- GEOS application header end ----";
			res.lines.add(tag + (comment.length() > 0 ? "\t" + comment : ""));
			return;
		}
		if(dnameRaw.equals(".output"))
		{
			res.lines.add("; " + trimmed.trim()
				+ " [geoLinker directive; no ca65 equivalent]"
				+ (comment.length() > 0 ? "\t" + comment : ""));
			return;
		}
		if(dnameRaw.equals(".vlir") || dnameRaw.equals(".seq") || dnameRaw.equals(".cbm"))
		{
			final String what = dnameRaw.equals(".vlir")
				? "VLIR structure marker"
				: (dnameRaw.equals(".seq") ? "sequential output marker" : "CBM program output marker");
			res.lines.add("; " + trimmed.trim()
				+ " [geoLinker " + what + "; configure in ld65 .cfg]"
				+ (comment.length() > 0 ? "\t" + comment : ""));
			return;
		}
		if(dnameRaw.equals(".mod"))
		{
			res.lines.add("; " + trimmed.trim()
				+ " [VLIR overlay module; define in ld65 .cfg]"
				+ (comment.length() > 0 ? "\t" + comment : ""));
			return;
		}
		// .if/.elseif: parenthesize so "A = B" is an equality expression,
		// not a re-definition of A by an assignment on the directive.
		if(dnameRaw.equals(".if") || dnameRaw.equals(".elseif"))
		{
			if(rewrittenOperand.trim().length() > 0)
				res.lines.add(makeLine(before, dnameRaw,
					"(" + rewrittenOperand.trim() + ")", comment));
			else
				res.lines.add(rewrittenCode + comment);
			return;
		}

		// ---- fallback: pass through with generic rewrite ----
		if(!KNOWN_NOOP.contains(dnameRaw))
		{
			final Integer cnt = res.unknownDirectives.get(dnameRaw);
			res.unknownDirectives.put(dnameRaw, (cnt == null) ? 1 : (cnt + 1));
			// unknown .words in these sources are prose/doc mentions (".D64",
			// ".CVT", ".etc") or decayed damage, never real GEOS directives;
			// comment the whole line rather than emit a ca65 syntax error.
			res.lines.add("; [" + trimmed.trim() + "] removed; unrecognized directive "
				+ "(source-doc prose/extension, not a ca65 or GEOS directive)"
				+ (comment.length() > 0 ? "\t" + comment : ""));
			return;
		}
		// data directives (.byte/.word/...) route through here; clean any
		// page-damage "w" tokens (rewriteCode's own cleanup only runs on the
		// operand path, which data directives do not use here).
		if(dnameRaw.equals(".byte") || dnameRaw.equals(".word") || dnameRaw.equals(".dbyt")
			|| dnameRaw.equals(".dword") || dnameRaw.equals(".addr") || dnameRaw.equals(".faraddr")
			|| dnameRaw.equals(".res"))
			res.lines.add(stripDataJunk(rewrittenCode) + comment);
		else
			res.lines.add(rewrittenCode + comment);
	}

	/**
	 * 
	 * @param args
	 */
	private static void runLinkMode(final String[] args)
	{
		File lnk = null;
		File cfgOverride = null;
		final List<File> srcdirs = environmentSourceDirs();
		File outdir = null;
		int overlaySizeOverride = 0;
		for(int i = 1; i < args.length; i++)
		{
			if("--outdir".equalsIgnoreCase(args[i]) && (i + 1 < args.length))
				outdir = new File(args[++i]);
			else
			if("--srcdir".equalsIgnoreCase(args[i]) && (i + 1 < args.length))
				srcdirs.add(new File(args[++i]));
			else
			if("--cfg".equalsIgnoreCase(args[i]) && (i + 1 < args.length))
				cfgOverride = new File(args[++i]);
			else
			if("--overlaysize".equalsIgnoreCase(args[i]) && (i + 1 < args.length))
			{
				try
				{
					overlaySizeOverride = Integer.decode(args[++i]).intValue();
				}
				catch(final Exception e)
				{
					overlaySizeOverride = 0;
				}
			}
			else
			if(lnk == null)
				lnk = new File(args[i]);
		}
		if(lnk == null || !lnk.exists())
		{
			System.err.println("Error: --lnk requires an existing linker manifest (.lnk.cvt) file");
			usage();
			System.exit(-1);
			return;
		}
		final File lnkParent = lnk.getParentFile();
		srcdirs.add(0, lnkParent);
		// pre-assembled resource docs (fonts, printer graphics referenced by the
		// manifest as F/... or bare names) live in the project's obj/ directory.
		final File lnkProject = lnkParent.getParentFile();
		if(lnkProject != null)
		{
			final File objDir = new File(lnkProject, "obj");
			if(objDir.isDirectory() && !srcdirs.contains(objDir))
				srcdirs.add(objDir);
		}
		if(outdir == null)
			outdir = new File(lnkParent, "build");
		if(!outdir.exists() && !outdir.mkdirs())
		{
			System.err.println("Error: cannot create output dir: " + outdir);
			System.exit(-1);
			return;
		}
		final File incDir = new File(outdir, "inc");
		if(!incDir.exists() && !incDir.mkdirs())
		{
			System.err.println("Error: cannot create include dir: " + incDir);
			System.exit(-1);
			return;
		}
		try
		{
			final LnkManifest manifest;
			final GeoRWriter lnkDoc = new GeoRWriter(lnk, false);
			manifest = parseLnk(extractLines(lnkDoc));
			System.out.println("GeoAsmConv --lnk " + lnk.getName()
				+ " (" + manifest.structure + " app '" + (manifest.appName != null ? manifest.appName : "") + "')");
			if(manifest.appName == null)
				manifest.appName = lnk.getName().replaceAll("(?i)\\.lnk.*$", "").replaceAll("\\.cvt$", "");
			rundownLink(manifest, lnk, srcdirs, outdir, incDir, overlaySizeOverride, cfgOverride);
		}
		catch(final Exception e)
		{
			System.err.println("Error: " + ((e.getMessage() != null) ? e.getMessage() : e.toString()));
			if(e.getMessage() == null)
				e.printStackTrace();
			System.exit(-1);
		}
	}

	/** 
	 * A pre-assembled GEOS relocatable object recovered from a project's obj/
	 * directory (e.g. a font resource referenced as F/name.rel in the linker
	 * manifest).  The .cvt body is: a 254-byte definition/relocation block,
	 * the loaded segment bytes, then a final 254-byte trailer holding the
	 * segment descriptor (loaded length, base) and a table of exported
	 * symbols.  The original source for these is not in the tree, so the
	 * converter cannot re-assemble them; it instead emits the loaded segment
	 * verbatim as a ca65 data module, which is exactly the bytes geoLinker
	 * placed in the output (the object carries no pending relocations that
	 * the linker resolves, as verified against the reference binaries). */
	private static final class RelObject
	{
		final byte[]	data;
		final String[]	symNames;
		final int[]		symVals;

		RelObject(final byte[] data, final String[] symNames, final int[] symVals)
		{
			this.data = data;
			this.symNames = symNames;
			this.symVals = symVals;
		}
	}

	private static byte[] readBytes(final File f) throws IOException
	{
		final ByteArrayOutputStream bout = new ByteArrayOutputStream();
		FileInputStream in = null;
		try
		{
			in = new FileInputStream(f);
			final byte[] buf = new byte[8192];
			int n;
			while((n = in.read(buf)) > 0)
				bout.write(buf, 0, n);
		}
		finally
		{
			if(in != null)
			{
				try
				{
					in.close();
				}
				catch(final IOException e)
				{
				}
			}
		}
		return bout.toByteArray();
	}

	/**
	 *  Parse a pre-assembled GEOS .rel object, or return null if the document
	 * does not have the expected object shape (in which case the caller keeps
	 * the existing "pre-assembled object, not assembly source" skip).
	 *
	 * @param f the .rel.cvt document
	 * @return the recovered segment and exports, or null
	 * @throws IOException on read failure
	 */
	private static RelObject parseRelObject(final File f) throws IOException
	{
		final byte[] raw = readBytes(f);
		// 2 blocks of GEOS file header (254 each) + at least one trailer block
		if((raw == null) || (raw.length < (508 + 254)))
			return null;
		final int t = raw.length - 254;   // last block: descriptor + symbol table
		final int len = (raw[t] & 0xff) | ((raw[t + 1] & 0xff) << 8);
		//final int base = (raw[t + 2] & 0xff) | ((raw[t + 3] & 0xff) << 8);
		final int dataStart = 508 + 0xfe;
		if((len <= 0) || ((dataStart + len) > t))
			return null;
		final List<String> names = new ArrayList<String>();
		final List<Integer> vals = new ArrayList<Integer>();
		for(int p = t + 4; (p + 10) <= raw.length; p += 10)
		{
			final StringBuilder nm = new StringBuilder();
			int z = 0;
			for(; z < 8; z++)
			{
				final int c = raw[p + z] & 0x7f;
				if(c == 0)
					break;
				nm.append((char)c);
			}
			if(z == 0)
				break;
			final String name = nm.toString();
			if(!name.matches("[A-Za-z_][A-Za-z0-9_]*"))
				break;
			names.add(name);
			vals.add(Integer.valueOf((raw[p + 8] & 0xff) | ((raw[p + 9] & 0xff) << 8)));
		}
		if(names.isEmpty())
			return null;
		final byte[] data = new byte[len];
		System.arraycopy(raw, dataStart, data, 0, len);
		final int[] sv = new int[vals.size()];
		for(int i = 0; i < sv.length; i++)
			sv[i] = vals.get(i).intValue();
		return new RelObject(data, names.toArray(new String[names.size()]), sv);
	}

	/**
	 *  Emit one pre-assembled object's loaded segment as a ca65 module for the
	 * given VLIR record, defining its exported labels at their segment
	 * offsets.
	 *
	 * @param outDir the build output directory
	 * @param disp base name for the generated source (no extension)
	 * @param record the VLIR record number (0 = resident main)
	 * @param ro the parsed object
	 * @return the generated .s file
	 * @throws IOException on write failure
	 */
	private static File emitRelObject(final File outDir, final String disp,
		final int record, final RelObject ro) throws IOException
	{
		final List<String> out = new ArrayList<String>();
		out.add("; Generated by GeoAsmConv v" + D64Base.EMUTIL_VERSION + " (Emutil).");
		out.add("; Pre-assembled GEOS object '" + disp + "': loaded segment (" + ro.data.length
			+ " bytes) emitted verbatim into VLIR record " + record + ".");
		out.add("");
		out.add(".segment\t\"" + (record > 0 ? ("OVERLAY" + record) : "CODE") + "\"");
		final Map<Integer, List<String>> labels = new TreeMap<Integer, List<String>>();
		for(int i = 0; i < ro.symNames.length; i++)
		{
			final Integer off = Integer.valueOf(ro.symVals[i] & 0xffff);
			List<String> l = labels.get(off);
			if(l == null)
			{
				l = new ArrayList<String>();
				labels.put(off, l);
			}
			l.add(ro.symNames[i]);
		}
		int pos = 0;
		for(final Map.Entry<Integer, List<String>> e : labels.entrySet())
		{
			final int off = e.getKey().intValue();
			if(off > pos)
			{
				emitRelBytes(out, ro.data, pos, off);
				pos = off;
			}
			for(final String nm : e.getValue())
				out.add(nm + ":");
		}
		if(pos < ro.data.length)
			emitRelBytes(out, ro.data, pos, ro.data.length);
		final File f = new File(outDir, disp + ".r" + record + ".s");
		writeFile(f, out);
		return f;
	}

	private static void emitRelBytes(final List<String> out, final byte[] data, final int from, final int to)
	{
		for(int i = from; i < to; i += 16)
		{
			final StringBuilder sb = new StringBuilder("\t.byte\t");
			for(int k = i; (k < to) && (k < (i + 16)); k++)
			{
				if(k > i)
					sb.append(',');
				final String h = Integer.toHexString(data[k] & 0xff);
				sb.append('$').append(h.length() < 2 ? "0" : "").append(h);
			}
			out.add(sb.toString());
		}
	}

	private static void rundownLink(final LnkManifest manifest, final File lnk,
		final List<File> srcdirs, final File outdir, final File incDir,
		final int overlaySizeOverride, final File cfgOverride)
		throws Exception
	{
		// Definitions docs (equates/macros, no code) are discovered from the
		// actual .include operands the sources use, and their macros are parsed
		// up front.  No file name is assumed.
		final Map<String, String> defIncludes = new LinkedHashMap<String, String>();
		final List<File> defDocs = new ArrayList<File>();
		final Map<String, MacroInfo> macros = new LinkedHashMap<String, MacroInfo>();

		// resolve modules from the manifest
		final LinkedHashMap<String, ModulePlan> plans = new LinkedHashMap<String, ModulePlan>();
		for(final LnkRecord rec : manifest.records)
		{
			for(final String rel : rec.rels)
			{
				final File doc = resolveLnk(rel, srcdirs);
				final String key = relKey(rel);
				if(doc == null)
				{
					System.out.println("  skip: no source doc for '" + rel + "' (resource not converted)");
					continue;
				}
				try
				{
					new GeoRWriter(doc, false);   // must be a convertible source doc
				}
				catch(final Exception e)
				{
					System.out.println("  skip: '" + rel + "' is a pre-assembled object ("
						+ doc.getName() + "), not assembly source");
					continue;
				}
				ModulePlan plan = plans.get(key);
				if(plan == null)
				{
					plan = new ModulePlan(rel, doc.getName().substring(0, doc.getName().length() - 4), doc);
					plans.put(key, plan);
				}
				plan.addRecord(rec.num);
			}
		}
		// discover definitions docs and textually-included module docs (e.g.
		// .include MODmodem) from the actual include operands
		boolean changed = true;
		while(changed)
		{
			changed = false;
			final List<ModulePlan> copy = new ArrayList<ModulePlan>(plans.values());
			for(final ModulePlan plan : copy)
			{
				for(final String[] inc : bareIncludes(plan.doc))
				{
					final String operand = inc[0];
					final String inckey = inc[1];
					if(defIncludes.containsKey(inckey))
						continue;
					final File idoc = resolveInclude(operand, srcdirs);
					if(idoc == null)
						continue;
					boolean defs;
					try
					{
						defs = isDefinitionsDoc(extractLines(new GeoRWriter(idoc, false)));
					}
					catch(final Exception e)
					{
						continue;   // not a convertible source doc
					}
					if(defs)
					{
						defIncludes.put(inckey, definitionsIncludeName(operand));
						if(!defDocs.contains(idoc))
							defDocs.add(idoc);
						changed = true;
						continue;
					}
					if(idoc.equals(plan.doc))
						continue;
					final String ikey = relKey(operand);
					ModulePlan iplan = plans.get(ikey);
					if(iplan == null)
					{
						iplan = new ModulePlan(operand,
							idoc.getName().substring(0, idoc.getName().length() - 4), idoc);
						plans.put(ikey, iplan);
						changed = true;
					}
					final Set<Integer> addRecs = new TreeSet<Integer>(plan.records);
					addRecs.removeAll(iplan.records);
					if(!addRecs.isEmpty())
					{
						for(final Integer r : addRecs)
							iplan.addRecord(r.intValue());
						changed = true;
					}
				}
			}
		}
		// the header doc may also .include a definitions doc
		if(manifest.headerRel != null)
		{
			final File hdoc0 = resolveLnk(manifest.headerRel, srcdirs);
			if(hdoc0 != null)
			{
				try
				{
					for(final String[] inc : bareIncludes(hdoc0))
					{
						if(defIncludes.containsKey(inc[1]))
							continue;
						final File idoc = resolveInclude(inc[0], srcdirs);
						if((idoc == null)
							|| !isDefinitionsDoc(extractLines(new GeoRWriter(idoc, false))))
							continue;
						defIncludes.put(inc[1], definitionsIncludeName(inc[0]));
						if(!defDocs.contains(idoc))
							defDocs.add(idoc);
					}
				}
				catch(final Exception e)
				{
				}
			}
		}
		for(final File dd : defDocs)
			macros.putAll(parseMacros(extractLines(new GeoRWriter(dd, false))));
		if(!defDocs.isEmpty())
			System.out.println("  macros: parsed " + macros.size() + " .macro definition(s) from "
				+ defDocs.get(0).getName()
				+ ((defDocs.size() > 1) ? (" (+" + (defDocs.size() - 1) + " more)") : ""));
		else
			System.out.println("  warning: no macro/equate definitions doc found in source dirs");
		// all definitions keys share one generated include
		String shimName = null;
		for(final String n : defIncludes.values())
		{
			shimName = n;
			break;
		}
		if(shimName != null)
			for(final Map.Entry<String, String> e : defIncludes.entrySet())
				e.setValue(shimName);

		// header doc -> app metadata / init symbol / icon
		String init = "Main";
		String[] hinfo = null;
		String[] haddrs = null;
		int htype = -1;
		String iconFile = null;
		int colFlag = -1;
		if(manifest.headerRel != null)
		{
			final File hdoc = resolveLnk(manifest.headerRel, srcdirs);
			if(hdoc != null)
			{
				hinfo = parseHeaderInfo(hdoc);
				haddrs = parseHeaderAddrs(hdoc);
				htype = parseHeaderType(hdoc, defDocs);
				colFlag = headerColumnFlag(hdoc);
				final String hs = headerInitSymbol(hdoc);
				if(hs != null)
					init = hs;
				// the header's app icon is an embedded geoWrite graphic; decode
				// its RLE bitmap back to the 3x21 sprite the header stores.
				final byte[] icon = decodeHeaderIcon(hdoc);
				if(icon != null)
				{
					final byte[] ic = new byte[Math.min(63, icon.length)];
					System.arraycopy(icon, 0, ic, 0, ic.length);
					writeFileBytes(new File(outdir, "icon.raw"), ic);
					iconFile = "icon.raw";
				}
			}
		}
		else
			System.out.println("  warning: manifest has no .header rel; using generic header info");

		// shim: generated now, written after module emission so module symbols
		// can shadow any colliding definitions-doc equate (see below).
		Set<String> shimDefs = new HashSet<String>();
		String[] shim = null;
		if(!defDocs.isEmpty())
			shim = genShim(defDocs, shimName);
		else
			System.out.println("  warning: no definitions include generated (no macro/equate doc found)");

		// per-record module emission: geoAssembler appended every module of a record
		// into ONE assembly (symbols visible across modules), so each record becomes
		// one ca65 object too.  Module files are emitted individually so that textual
		// .include rewrites keep working; rec<N>.s then textually includes them.
		final Map<Integer, List<File>> recFiles = new TreeMap<Integer, List<File>>();
		final Map<Integer, List<String>> recListed = new TreeMap<Integer, List<String>>();
		final Set<String> emitted = new HashSet<String>();
		int maxRec = 0;
		for(final LnkRecord rec : manifest.records)
			maxRec = Math.max(maxRec, rec.num);
		for(final ModulePlan pl0 : plans.values())
			for(final Integer r0 : pl0.records)
				maxRec = Math.max(maxRec, r0.intValue());
		final int[] recEst = new int[maxRec + 1];
		boolean anyOverlay = false;
		for(final LnkRecord rec : manifest.records)
		{
			if(rec.num > 0)
				anyOverlay = true;
			for(final String rel : rec.rels)
			{
				final ModulePlan plan = plans.get(relKey(rel));
				if(plan == null)
				{
					// a pre-assembled resource object (e.g. F/font resources) has no
					// geoWrite source doc; emit its loaded segment verbatim instead so
					// its exported symbols resolve and its bytes reach the output.
					final File odoc = resolveLnk(rel, srcdirs);
					if(odoc != null)
					{
						final RelObject ro = parseRelObject(odoc);
						if(ro != null)
						{
							final String disp = odoc.getName().replaceAll("(?i)\\.rel\\.cvt$", "")
								.replaceAll("(?i)\\.cvt$", "");
							final String okey = disp + "@" + rec.num;
							if(!emitted.contains(okey))
							{
								emitted.add(okey);
								final File of = emitRelObject(outdir, disp, rec.num, ro);
								recEst[rec.num] += ro.data.length;
								List<File> fl = recFiles.get(Integer.valueOf(rec.num));
								if(fl == null)
								{
									fl = new ArrayList<File>();
									recFiles.put(Integer.valueOf(rec.num), fl);
								}
								fl.add(of);
								List<String> ll = recListed.get(Integer.valueOf(rec.num));
								if(ll == null)
								{
									ll = new ArrayList<String>();
									recListed.put(Integer.valueOf(rec.num), ll);
								}
								ll.add(of.getName());
							}
						}
					}
					continue;
				}
				final String ekey = plan.disp + "@" + rec.num;
				if(!emitted.contains(ekey))
				{
					emitted.add(ekey);
					recEst[rec.num] += estimateBytes(
						emitModule(outdir, plan, rec.num, macros, plans, defIncludes).lines);
					List<File> fl = recFiles.get(Integer.valueOf(rec.num));
					if(fl == null)
					{
						fl = new ArrayList<File>();
						recFiles.put(Integer.valueOf(rec.num), fl);
					}
					fl.add(new File(outdir, plan.disp + ".r" + rec.num + ".s"));
					List<String> ll = recListed.get(Integer.valueOf(rec.num));
					if(ll == null)
					{
						ll = new ArrayList<String>();
						recListed.put(Integer.valueOf(rec.num), ll);
					}
					ll.add(plan.disp + ".r" + rec.num + ".s");
				}
			}
		}
		// included (discovered) modules: textual, emitted per record, no object
		for(final ModulePlan plan : plans.values())
		{
			for(final Integer r : plan.records)
			{
				final String ekey = plan.disp + "@" + r.intValue();
				if(emitted.contains(ekey))
					continue;
				emitted.add(ekey);
				try
				{
					recEst[r.intValue()] += estimateBytes(
						emitModule(outdir, plan, r.intValue(), macros, plans, defIncludes).lines);
					List<File> fl = recFiles.get(r);
					if(fl == null)
					{
						fl = new ArrayList<File>();
						recFiles.put(r, fl);
					}
					fl.add(new File(outdir, plan.disp + ".r" + r.intValue() + ".s"));
				}
				catch(final Exception e)
				{
					System.out.println("  warning: include module '" + plan.rel
						+ "' failed to convert: " + e.getMessage());
				}
			}
		}

		// Write the shim.  Where a module defines a label of the same name as a
		// definitions-doc equate (e.g. geoLoadStar's "PrintBuf" buffer vs the system
		// `PrintBuf = $7906`), the original geoAssembler kept the equate and the
		// reference used the system address; the colliding module label is
		// dropped (its allocation kept) when records are materialized below.
		if(shim != null)
		{
			collectSymbols(java.util.Arrays.asList(shim), shimDefs, new HashSet<String>());
			writeFile(new File(incDir, shimName), shim);
		}

		// case-repair pass: doc extraction can flip character case in references
		// ("InitVPRG" vs def "InitVPrg"); geoAssembler was case-significant, so
		// restore each reference to its definition's spelling.  geoAssembler's
		// 8-significant-char rule is already baked into the emitted files.
		{
			// Case-damaged references must be repaired per record: a name with
			// two case spellings in different overlays (e.g. LastTnS / LastTNS)
			// is globally ambiguous, but within one record only one spelling is
			// present, so that record's own definition disambiguates it.
			final Set<String> globalDefs0 = new HashSet<String>(shimDefs);
			for(final Map.Entry<Integer, List<File>> e : recFiles.entrySet())
			{
				for(final File f : e.getValue())
					collectSymbols(readFile(f), globalDefs0, new HashSet<String>());
			}
			final Map<String, Set<String>> byLower = new HashMap<String, Set<String>>();
			for(final String d : globalDefs0)
			{
				final String low = d.toLowerCase();
				Set<String> spellings = byLower.get(low);
				if(spellings == null)
				{
					spellings = new HashSet<String>();
					byLower.put(low, spellings);
				}
				spellings.add(d);
			}
			final Set<String> safeGlobals = new HashSet<String>();
			for(final Set<String> spellings : byLower.values())
				if(spellings.size() == 1)
					safeGlobals.addAll(spellings);
			int repaired = 0;
			for(final Map.Entry<Integer, List<File>> e : recFiles.entrySet())
			{
				final Set<String> defs = new HashSet<String>(safeGlobals);
				for(final File f : e.getValue())
					collectSymbols(readFile(f), defs, new HashSet<String>());
				for(final File f : e.getValue())
					repaired += repairCase(readFile(f), defs, f);
			}
			if(repaired > 0)
				System.out.println("  case-repair: fixed " + repaired
					+ " case-damaged symbol reference(s)");
		}

		// startup entry stub (separate object; imports the app init address)
		emitStartup(outdir, init, manifest.appName);

		// per-record symbol sets: intra-record symbols are visible to each other;
		// only cross-record references need .import/.export.
		final Map<Integer, Set<String>> recDefs = new TreeMap<Integer, Set<String>>();
		final Map<Integer, Set<String>> recRefs = new TreeMap<Integer, Set<String>>();
		final Set<String> globalDefs = new HashSet<String>(shimDefs);
		for(final Map.Entry<Integer, List<File>> e : recFiles.entrySet())
		{
			final Set<String> defs = new HashSet<String>();
			final Set<String> refs = new HashSet<String>();
			for(final File f : e.getValue())
			{
				final Set<String> d = new HashSet<String>();
				final Set<String> r = new HashSet<String>();
				collectSymbols(readFile(f), d, r);
				defs.addAll(d);
				refs.addAll(r);
			}
			recDefs.put(e.getKey(), defs);
			recRefs.put(e.getKey(), refs);
			globalDefs.addAll(defs);
		}
		// cross-record constant equates must be shared textually: ca65 cannot use
		// an .import'ed symbol in an 8-bit immediate ("lda #DEFMTOP").  Collect
		// every numeric equate and inline it into each record's object.  A name
		// given different values in different records is record-specific (e.g.
		// MODMODEM=64 in one overlay, 128 in another): the first spelling seeds
		// records that do not define it, and each defining record keeps its own.
		final Map<String, String> constVal = new LinkedHashMap<String, String>();
		final Map<String, Set<String>> constVals = new HashMap<String, Set<String>>();
		final Pattern constP = Pattern.compile(
			"^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(?::=|=)\\s*([0-9$%A-Fa-fXx+\\-*/&|^()<> ]+)$");
		for(final Map.Entry<Integer, List<File>> e : recFiles.entrySet())
		{
			for(final File f : e.getValue())
			{
				for(final String c0 : readFile(f))
				{
					final Matcher cm = constP.matcher(stripComment(c0));
					if(cm.find())
					{
						final String v = cm.group(2).trim();
						if(!constVal.containsKey(cm.group(1)))
							constVal.put(cm.group(1), v);
						Set<String> vs = constVals.get(cm.group(1));
						if(vs == null)
						{
							vs = new HashSet<String>();
							constVals.put(cm.group(1), vs);
						}
						vs.add(v);
					}
				}
			}
		}
		final Set<String> constNames = new LinkedHashSet<String>(constVal.keySet());
		final List<String> constLines = new ArrayList<String>();
		final Set<String> conflictingConsts = new HashSet<String>();
		for(final Map.Entry<String, String> e : constVal.entrySet())
		{
			constLines.add(e.getKey() + "\t=\t" + e.getValue());
			final Set<String> vs = constVals.get(e.getKey());
			if((vs != null) && (vs.size() > 1))
				conflictingConsts.add(e.getKey());
		}
		// external references with no definition in any source doc are left
		// unresolved on purpose; the linker must report them rather than have
		// them papered over with invented stubs.
		final Set<String> extStubs = new TreeSet<String>();

		final Map<Integer, Set<String>> recExports = new TreeMap<Integer, Set<String>>();
		final Map<Integer, Set<String>> recImports = new TreeMap<Integer, Set<String>>();
		for(final Map.Entry<Integer, Set<String>> e : recRefs.entrySet())
		{
			final Integer r = e.getKey();
			final Set<String> myDefs = recDefs.get(r);
			final Set<String> imports = new HashSet<String>();
			for(final String s : e.getValue())
			{
				if(myDefs.contains(s) || shimDefs.contains(s) || constNames.contains(s))
					continue;
				if(extStubs.contains(s))
				{
					imports.add(s);
					continue;
				}
				if(!globalDefs.contains(s))
					continue;
				imports.add(s);
				for(final Map.Entry<Integer, Set<String>> de : recDefs.entrySet())
				{
					if(de.getKey().intValue() != r.intValue() && de.getValue().contains(s))
					{
						Set<String> ex = recExports.get(de.getKey());
						if(ex == null)
						{
							ex = new HashSet<String>();
							recExports.put(de.getKey(), ex);
						}
						ex.add(s);
						break;
					}
				}
			}
			if(!imports.isEmpty())
				recImports.put(r, imports);
		}
		// the Startup object references the app init address from record 0, but
		// only if some record actually defines it (a literal header init address
		// leaves no symbol; exporting an undeclared "Main" breaks the link).
		boolean initDefined = false;
		for(final Set<String> ds : recDefs.values())
		{
			if(ds.contains(init))
			{
				initDefined = true;
				break;
			}
		}
		if(initDefined)
		{
			Set<String> ex0 = recExports.get(Integer.valueOf(0));
			if(ex0 == null)
			{
				ex0 = new HashSet<String>();
				recExports.put(Integer.valueOf(0), ex0);
			}
			ex0.add(init);
		}

		// materialize rec<N>.s files (header + imports + exports + includes of listed modules)
		final List<String> objs = new ArrayList<String>();
		objs.add("Startup.o");
		// records that carry their own RAM (.ramsect inside an overlay) and the
		// full set of overlay records, needed to shape the ld65 memory layout.
		final Set<Integer> overlayRecords = new TreeSet<Integer>();
		final Set<Integer> ramOverlays = new TreeSet<Integer>();
		for(final Integer rk : recFiles.keySet())
		{
			if(rk.intValue() > 0)
				overlayRecords.add(rk);
		}
		for(final Map.Entry<Integer, List<File>> e : recFiles.entrySet())
		{
			final Integer r = e.getKey();
			final List<String> out = new ArrayList<String>();
			out.add("; Generated by GeoAsmConv v" + D64Base.EMUTIL_VERSION + " (Emutil).");
			out.add("; VLIR record " + r + (r.intValue() > 0
				? " -> OVERLAY" + r + " (.mod " + r + ")"
				: " -> main record 0, CODE"));
			out.add("; Merges all modules of the record into one ca65 object, matching");
			out.add("; the original geoAssembler's single-assembly symbol space.");
			out.add("");
			final Set<String> im = recImports.get(r);
			if((im != null) && !im.isEmpty())
			{
				final List<String> ims = new ArrayList<String>(im);
				java.util.Collections.sort(ims);
				out.add(".import\t" + joinStringsComma(ims));
				out.add("");
			}
			final Set<String> ex = recExports.get(r);
			if((ex != null) && !ex.isEmpty())
			{
				final List<String> exs = new ArrayList<String>(ex);
				java.util.Collections.sort(exs);
				out.add(".export\t" + joinStringsComma(exs));
				out.add("");
			}
			final List<String> ll = recListed.get(r);
			if((ll != null) && !ll.isEmpty())
			{
				// geoAssembler assembled each module separately, so per-module
				// ".eqin" equates (e.g. "LineNum = $8879") could repeat.  A record
				// is one ca65 object, so inline the modules and keep only the first
				// definition of each equate.  Shared numeric constants are emitted
				// first so cross-record "lda #CONST" references resolve at assembly.
				final Set<String> seenEq = new HashSet<String>();
				final Pattern eqP = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(?::=|=)\\s*");
				// equates supplied by this record's .include-only modules are
				// defined when the .include expands; emitting the shared preamble
				// copy too would redefine them in the same ca65 object.
				final Set<String> includeOnlyDefs = new HashSet<String>();
				final Set<String> recordDefs = new HashSet<String>();
				final Set<String> listedNames = new HashSet<String>(ll);
				final List<File> recAll = recFiles.get(r);
				if(recAll != null)
				{
					for(final File mf : recAll)
					{
						final Set<String> d = new HashSet<String>();
						collectSymbols(readFile(mf), d, new HashSet<String>());
						recordDefs.addAll(d);
						if(!listedNames.contains(mf.getName()))
							includeOnlyDefs.addAll(d);
					}
				}
				for(final String cl : constLines)
				{
					final Matcher cem = eqP.matcher(cl);
					if(cem.find())
					{
						final String cn = cem.group(1);
						if(includeOnlyDefs.contains(cn))
							continue;
						// a record-specific constant (MODMODEM, BANK_*) is left to
						// the module defining it in this record
						if(conflictingConsts.contains(cn) && recordDefs.contains(cn))
							continue;
						seenEq.add(cn);
					}
					out.add(cl);
				}
				final Pattern labP = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*):(.*)$");
				for(final String mf : ll)
				{
					for(final String mline : readFile(new File(outdir, mf)))
					{
						if(mline.startsWith(".segment") && (mline.indexOf("\"OVLRAM") >= 0))
							ramOverlays.add(r);
						final Matcher em = eqP.matcher(mline);
						if(em.find() && !seenEq.add(em.group(1)))
							continue;
						// a module label that collides with a definitions-doc equate
						// loses to the equate (geoAssembler kept the system value);
						// drop the label but keep whatever it allocated.
						final Matcher lm = labP.matcher(mline);
						if(lm.matches() && shimDefs.contains(lm.group(1)))
							out.add(lm.group(2));
						else
							out.add(mline);
					}
				}
			}
			writeFile(new File(outdir, "rec" + r + ".s"), out);
			objs.add("rec" + r + ".o");
		}

		// external-resource stub object (resident in main/CODE): a one-byte label
		// per unresolved symbol, addressable by both data pointers and "jsr".
		if(!extStubs.isEmpty())
		{
			final List<String> out = new ArrayList<String>();
			out.add("; Generated by GeoAsmConv v" + D64Base.EMUTIL_VERSION
				+ " (Emutil): stubs for external binary resources.");
			out.add(".segment\t\"CODE\"");
			final List<String> names = new ArrayList<String>(extStubs);
			out.add(".export\t" + joinStringsComma(names));
			for(final String s : names)
			{
				out.add(s + ":");
				out.add("\trts");
			}
			writeFile(new File(outdir, "stubs.s"), out);
			objs.add("stubs.o");
		}

		// grc resource + makefile
		final int overlaySize = chooseOverlaySize(recEst, overlaySizeOverride);
		writeFile(new File(outdir, manifest.appName + ".grc"),
			genGrc(manifest, hinfo, iconFile, anyOverlay, overlaySize, recEst));
		final Map<Integer, String> overlayPsect = new TreeMap<Integer, String>();
		for(final LnkRecord rec : manifest.records)
			if((rec.num > 0) && (rec.psect != null) && (rec.psect.length() > 0))
				overlayPsect.put(Integer.valueOf(rec.num), rec.psect);
		final String cfgName = writeLinkerConfig(outdir, manifest.appName,
			manifest.ramsect, manifest.psect, overlayRecords, ramOverlays, overlayPsect,
			cfgOverride);
		// generate the header assembly now, then rewrite the two fields grc65
		// cannot express (author padding, 40/80 column flag) from the H doc.
		boolean grcPrebuilt = false;
		if(runGrc65(outdir, manifest.appName))
		{
			final File sFile = new File(outdir, manifest.appName + ".s");
			patchGrcHeader(sFile, hinfo, colFlag, htype, haddrs);
			patchGrcRecords(sFile, manifest.ramsect != null, ramOverlays);
			grcPrebuilt = true;
		}
		writeFile(new File(outdir, "Makefile"), genMakefile(manifest, objs, cfgName, grcPrebuilt, shimName));

		System.out.println("  modules: " + plans.size() + " source doc(s), " + objs.size() + " object(s)");
		System.out.println("  output: " + outdir.getAbsolutePath());
		for(int i = 1; (recEst != null) && (i < recEst.length); i++)
		{
			System.out.println("    estimate record " + i + ": ~"
				+ String.format("0x%04x", Integer.valueOf(recEst[i])) + " bytes");
		}
	}

	private static ConvertResult emitModule(final File outDir, final ModulePlan plan,
		final int record, final Map<String, MacroInfo> macros,
		final Map<String, ModulePlan> plans, final Map<String, String> defIncludes) throws Exception
	{
		final GeoRWriter doc = new GeoRWriter(plan.doc, false);
		final List<String> lines = extractLines(doc);
		final ConvertOptions opts = new ConvertOptions();
		opts.linkMode = true;
		opts.macros = macros;
		opts.includeMap.putAll(defIncludes);
		if(record > 0)
			opts.overlay = Integer.valueOf(record);
		final Integer rec = Integer.valueOf(record);
		for(final ModulePlan other : plans.values())
		{
			if(other.equals(plan) || !other.records.contains(rec))
				continue;
			opts.includeMap.put(relKey(other.rel), other.disp + ".r" + record + ".s");
			opts.includeMap.put(docKey(other.doc), other.disp + ".r" + record + ".s");
		}
		final ConvertResult res = convert(lines, opts);
		// geoAssembler's psect counter is module-relative (starts at 0), so a
		// branch operand "$nn" targets offset $nn from the start of THIS module;
		// the near-branch displacement is then base-invariant.  Mark the module
		// origin and express such targets relative to it.  (A "$nn" that is not a
		// branch operand is an ordinary hex value and is left alone.)
		final Pattern brHex = Pattern.compile(
			"(?i)^(\\s*)(bcc|bcs|beq|bne|bmi|bpl|bvc|bvs)(\\s+)\\$([0-9A-Fa-f]{1,2})(\\s*(?:;.*)?)$");
		boolean usesOrigin = false;
		for(final String l : res.lines)
		{
			if(brHex.matcher(l).matches())
			{
				usesOrigin = true;
				break;
			}
		}
		if(usesOrigin)
		{
			final StringBuilder lb = new StringBuilder("__mstart_");
			for(int i = 0; i < plan.disp.length(); i++)
			{
				final char c = plan.disp.charAt(i);
				lb.append(((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) ? c : '_');
			}
			lb.append("__");
			final String originLabel = lb.toString();
			for(int i = 0; i < res.lines.size(); i++)
			{
				final Matcher bm = brHex.matcher(res.lines.get(i));
				if(bm.matches())
				{
					res.lines.set(i, bm.group(1) + bm.group(2) + bm.group(3)
						+ originLabel + "+$" + bm.group(4) + bm.group(5));
				}
			}
			boolean placed = false;
			for(int i = 0; i < res.lines.size(); i++)
			{
				if(res.lines.get(i).trim().startsWith(".segment"))
				{
					res.lines.add(i + 1, originLabel + ":");
					placed = true;
					break;
				}
			}
			if(!placed)
				res.lines.add(0, originLabel + ":");
		}
		// surface per-module problems: these become ca65/ld65 errors otherwise
		for(final String w : res.warnings)
			System.out.println("  warning: [" + plan.disp + ".r" + record + "] " + w);
		for(final Map.Entry<String, Integer> e : res.unknownDirectives.entrySet())
		{
			System.out.println("  warning: [" + plan.disp + ".r" + record + "] "
				+ e.getValue() + "x '" + e.getKey() + "' - unrecognized directive; commented out");
		}
		final List<String> out = new ArrayList<String>();
		out.add("; Converted from " + plan.doc.getName() + " by GeoAsmConv v"
			+ D64Base.EMUTIL_VERSION + " (Emutil)");
		out.add("; geoAssembler module '" + plan.rel + "' -> ca65 object for VLIR record "
			+ record + " (" + ((record > 0) ? "OVERLAY" + record + ", .mod " + record
				: "main record 0, CODE") + ").");
		out.add("");
		out.addAll(res.lines);
		writeFile(new File(outDir, plan.disp + ".r" + record + ".s"), out);
		return res;
	}

	private static void emitStartup(final File outDir, final String init, final String appName)
		throws Exception
	{
		final List<String> out = new ArrayList<String>();
		out.add("; Generated by GeoAsmConv v" + D64Base.EMUTIL_VERSION + " (Emutil).");
		out.add("; GEOS initializes the application at the record-0 entry address");
		out.add("; (__STARTUP_RUN__, the VLIR0 run start).  The STARTUP segment is");
		out.add("; deliberately EMPTY so __STARTUP_RUN__ equals the first CODE byte --");
		out.add("; the app's entry routine -- exactly as geoProgrammer emitted it, with");
		out.add("; no cc65 C-runtime prologue or jump shim shifting the code.");
		out.add("");
		out.add(".segment \"STARTUP\"");
		writeFile(new File(outDir, "Startup.s"), out);
	}

	/** 
	 * Build the generated ca65 include from the discovered definitions doc(s):
	 * equates and .macro definitions, with macro bodies converted so their
	 * private labels become ca65 anonymous labels.  The file is named from the
	 * .include operand the source used, never from a hardcoded name.
	 *
	 * @param defDocs the definitions docs, in discovery order
	 * @param shimName the generated include's file name
	 */
	private static String[] genShim(final List<File> defDocs, final String shimName) throws Exception
	{
		final String guard = "__GXINL_" + shimName.replaceAll("[^A-Za-z0-9]", "_") + "__";
		final List<String> shim = new ArrayList<String>();
		shim.add("; Generated from " + defDocs.get(0).getName() + " by GeoAsmConv v"
			+ D64Base.EMUTIL_VERSION + " (Emutil).");
		shim.add("; Equates and macros from the .include'd definitions doc, as ca65 source.");
		shim.add("; Include guard: several record-0 modules .include this file.");
		shim.add(".ifndef\t" + guard);
		shim.add(guard + " = 1");
		shim.add("");
		int fragments = 0;
		for(final File defDoc : defDocs)
		{
			final GeoRWriter doc = new GeoRWriter(defDoc, false);
			final List<String> src = extractLines(doc);
			final ConvertResult conv = convert(src, new ConvertOptions());
			for(int i = 0; i < conv.lines.size(); i++)
			{
				final String line = conv.lines.get(i);
				final String t = line.trim();
				if(t.startsWith(".macro"))
				{
					// convert the body as a unit so label references can be
					// resolved toward their definitions
					int end = i + 1;
					while((end < conv.lines.size())
						&& !conv.lines.get(end).trim().equals(".endmacro"))
						end++;
					if(end >= conv.lines.size())
						break;   // unterminated macro; convert() already warned
					shim.add(line);
					shim.addAll(anonMacroBody(conv.lines.subList(i + 1, end), true));
					shim.add(conv.lines.get(end));
					shim.add("");
					i = end;
					continue;
				}
				if(t.equals(".endmacro"))
					continue;   // stray (unbalanced); convert() already warned
				if(t.length() == 0 || t.startsWith(";"))
				{
					shim.add(line);
					continue;
				}
				if(t.startsWith("."))
				{
					shim.add(line);   // directive (e.g. .macpack), keep verbatim
					continue;
				}
				if(t.matches("[A-Za-z_][A-Za-z0-9_]*\\s*(:=|=).*"))
				{
					shim.add(line);
					continue;
				}
				fragments++;         // page-overlap fragment / stray body bytes
			}
		}
		if(fragments > 0)
			System.out.println("  shim: dropped " + fragments
				+ " stray top-level fragment line(s) (page-overlap artifacts)");
		shim.add(".endif\t; " + guard);
		return shim.toArray(new String[0]);
	}

	/** Rewrite a converted macro body's private labels into ca65 anonymous
	 *  labels (":", ":+", ":-").  Anonymous labels are positional, so every
	 *  expansion gets fresh ones; no .scope is used, which would break the
	 *  cheap-local context at the expansion site.  With {@code verbose},
	 *  page-damage fragments are reported (as during shim generation); inline
	 *  macro expansion passes {@code false} to keep the output quiet.
	 *
	 * @param body the macro body lines
	 * @param verbose whether to report page-damage fragments
	 */
	private static List<String> anonMacroBody(final List<String> body, final boolean verbose)
	{
		// label definitions: "NAME:" at the start of a body line
		final Map<String, Integer> defs = new LinkedHashMap<String, Integer>();
		for(int i = 0; i < body.size(); i++)
		{
			final Matcher lm = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*):")
				.matcher(body.get(i));
			if(lm.find() && !defs.containsKey(lm.group(1)))
				defs.put(lm.group(1), Integer.valueOf(i));
		}
		final List<Integer> defPositions = new ArrayList<Integer>(defs.values());
		final List<String> out = new ArrayList<String>(body.size());
		for(int i = 0; i < body.size(); i++)
		{
			String line = body.get(i);
			// page damage inside a macro body: an orphan token fused before a
			// real opcode ("uy lda source+0"); geoAssembler would have rejected it
			{
				final String[] parts = line.trim().split("\\s+");
				if((parts.length >= 2)
				&& (parts[0].indexOf(':') < 0)
				&& !parts[0].startsWith(".")
				&& !parts[0].endsWith("$")
				&& !OPCODES.contains(parts[0].toLowerCase())
				&& OPCODES.contains(parts[1].toLowerCase()))
				{
					if(verbose)
						System.out.println("  shim: removed orphan token '" + parts[0]
							+ "' before an opcode in a macro body (page damage)");
					line = line.replaceFirst("^\\s*" + Pattern.quote(parts[0]) + "\\s+", "");
				}
			}
			for(final String l : defs.keySet())
				line = line.replaceFirst("^(\\s*)" + Pattern.quote(l) + ":", "$1:");
			for(final Map.Entry<String, Integer> e : defs.entrySet())
			{
				final String l = e.getKey();
				final int def = e.getValue().intValue();
				final boolean fwd = def > i;
				// the anonymous label must be the NEAREST one in that direction,
				// otherwise ":+"/":-" would target a different label
				boolean nearest = true;
				for(final Integer d : defPositions)
				{
					if(d.intValue() == def)
						continue;
					if(fwd ? ((i < d.intValue()) && (d.intValue() < def)) : ((def < d.intValue()) && (d.intValue() < i)))
					{
						nearest = false;
						break;
					}
				}
				if(!nearest)
				{
					if(verbose)
						System.out.println("  shim: macro label '" + l
							+ "' is not the nearest anonymous label; reference kept verbatim");
					continue;
				}
				// only standalone trailing operands are rewritten
				line = line.replaceFirst("\\b" + Pattern.quote(l) + "(?=\\s*($|;))",
					fwd ? ":+" : ":-");
			}
			out.add(line);
		}
		return out;
	}

	private static String[] parseHeaderInfo(final File hdoc) throws Exception
	{
		final GeoRWriter doc = new GeoRWriter(hdoc, false);
		final List<String> strings = new ArrayList<String>();
		final List<Boolean> strCr = new ArrayList<Boolean>();
		for(final String line : extractLines(doc))
		{
			final int qi = line.indexOf('"');
			if(qi >= 0 && line.substring(0, qi).trim().startsWith(".byte"))
			{
				final Matcher sm = Pattern.compile("\"([^\"]*)\"").matcher(line);
				if(sm.find())
				{
					strings.add(sm.group(1));
					// ".byte \"text\",13" stores a CR after the text; the header
					// info field keeps those separators.
					boolean cr = false;
					final Matcher nb = Pattern.compile("^\\s*,\\s*(\\$?)([0-9A-Fa-f]+)")
						.matcher(line.substring(sm.end()));
					if(nb.find())
					{
						final int val = Integer.parseInt(nb.group(2),
							nb.group(1).equals("$") ? 16 : 10);
						cr = (val == 13);
					}
					strCr.add(Boolean.valueOf(cr));
				}
			}
		}
		String dos = null;
		String version = "1.0";
		String author = "";
		final StringBuilder info = new StringBuilder();
		if(strings.size() > 0)
		{
			final String namever = strings.get(0);
			// the H header's name field is a 12-char class name followed by a
			// 4-char version incl. leading space ("LS Presenter 1.8"); the class
			// may itself contain spaces, so take the fixed 12-char field rather
			// than the first whitespace-delimited token.
			dos = (namever.length() >= 12) ? namever.substring(0, 12) : namever.trim();
			final String[] toks = namever.trim().split("\\s+");
			if(namever.length() > 12)
			{
				String v = namever.substring(12);
				if(v.length() > 4)
					v = v.substring(0, 4);
				version = v;
			}
			else
			if(toks.length > 1)
			{
				String v = toks[toks.length - 1];
				if(v.length() > 4)
					v = v.substring(0, 4);
				version = v;
			}
			else
				version = (strings.size() > 1) ? "1.0" : version;
		}
		if(strings.size() > 1)
			author = strings.get(1); // keep trailing padding: GEOS pads the author field
		for(int i = 2; i < strings.size(); i++)
		{
			info.append(strings.get(i));
			if(strCr.get(i).booleanValue())
				info.append('\r');
		}
		return new String[] { (dos != null) ? dos : "", version, author, info.toString() };
	}

	/**
	 * The H header names the GEOS file type with a bare equate right after the
	 * "Commodore type" byte (".byte PRINTER"); grc65 only supports APPLICATION,
	 * so resolve the equate from the discovered definitions docs and patch the
	 * header afterwards.
	 *
	 * @param hdoc the H header document
	 * @param defDocs the discovered definitions docs (may be empty)
	 * @return the GEOS file type byte, or -1 if not found
	 */
	private static int parseHeaderType(final File hdoc, final List<File> defDocs) throws Exception
	{
		final GeoRWriter doc = new GeoRWriter(hdoc, false);
		String name = null;
		for(final String line : extractLines(doc))
		{
			final String t = stripComment(line).trim();
			if(!t.startsWith(".byte"))
				continue;
			final String rest = t.substring(5).trim();
			if(rest.matches("[A-Za-z_][A-Za-z0-9_]*"))
			{
				name = rest;
				break;
			}
		}
		if((name == null) || (defDocs == null) || defDocs.isEmpty())
			return -1;
		final Pattern eq = Pattern.compile("^\\s*" + Pattern.quote(name)
			+ "\\s*=\\s*(\\$?)([0-9A-Fa-f]+)\\s*$");
		for(final File defDoc : defDocs)
		{
			final GeoRWriter gd = new GeoRWriter(defDoc, false);
			for(final String line : extractLines(gd))
			{
				final Matcher m = eq.matcher(stripComment(line).trim());
				if(m.find())
					return Integer.parseInt(m.group(2), m.group(1).equals("$") ? 16 : 10);
			}
		}
		return -1;
	}

	/** 
	 * The H header spells out the header's load/end/init words
	 * (".word $7900 ;load address", etc.); the original build stored those
	 * values, not the linker's computed addresses.
	 *
	 * @param hdoc the H header document
	 * @return [load, end, init] raw expressions, entries unset when absent
	 */
	private static String[] parseHeaderAddrs(final File hdoc) throws Exception
	{
		final GeoRWriter doc = new GeoRWriter(hdoc, false);
		final String[] out = new String[3];
		for(final String line : extractLines(doc))
		{
			final String lc = line.toLowerCase();
			final int idx;
			if(lc.indexOf("load address") >= 0)
				idx = 0;
			else
			if(lc.indexOf("end address") >= 0)
				idx = 1;
			else
			if(lc.indexOf("init address") >= 0)
				idx = 2;
			else
				continue;
			final Matcher m = Pattern.compile("\\.word\\s+(\\S+)").matcher(stripComment(line));
			if(m.find())
				out[idx] = m.group(1);
		}
		return out;
	}

	private static String headerInitSymbol(final File hdoc) throws Exception
	{
		final GeoRWriter doc = new GeoRWriter(hdoc, false);
		final Pattern wp = Pattern.compile("(?i)\\.word\\s+([A-Za-z_][A-Za-z0-9_]*)");
		for(final String line : extractLines(doc))
		{
			// the "init"/"load" marker lives in the comment, so test the raw
			// line before stripping it; then read the symbol from the code part
			if(line.toLowerCase().indexOf("init") < 0)
				continue;
			final String t = stripComment(line);
			final Matcher m = wp.matcher(t);
			if(m.find())
			{
				final String sym = m.group(1);
				// geoAssembler 8-significant-char symbol rule
				return (sym.length() > 8) ? sym.substring(0, 8) : sym;
			}
		}
		return null;
	}

	private static String[] genGrc(final LnkManifest manifest, final String[] hinfo,
		final String iconFile, final boolean anyOverlay, final int overlaySize, final int[] recEst)
	{
		String name = (manifest.appName != null) ? manifest.appName.trim() : "APP";
		String cls = name;
		String version = "1.0";
		String author = "";
		String info = "";
		if(hinfo != null)
		{
			if(hinfo[0].length() > 0)
				cls = hinfo[0];
			version = hinfo[1];
			author = hinfo[2];
			info = hinfo[3];
		}
		final StringBuilder sb = new StringBuilder();
		sb.append("HEADER APPLICATION \"").append(name).append("\" \"").append(cls)
			.append("\" \"").append(version).append("\" {\n");
		if(author.length() > 0)
			sb.append("    author    \"").append(author).append("\"\n");
		if(info.length() > 0)
			sb.append("    info      \"").append(info.replace("\r", "")).append("\"\n");
		if(iconFile != null)
			sb.append("    icon      \"").append(iconFile).append("\"\n");
		sb.append("    date      24 01 01 00 00\n");
		sb.append("    structure ").append(manifest.structure.toUpperCase()).append("\n");
		sb.append("}\n\n");
		if(anyOverlay)
		{
			final StringBuilder nums = new StringBuilder();
			int count = 0;
			for(int i = 1; (recEst != null) && (i < recEst.length); i++)
			{
				if(recEst[i] > 0)
				{
					if(count > 0)
						nums.append(' ');
					nums.append(i);
					count++;
				}
			}
			sb.append("MEMORY {\n");
			sb.append("    overlaysize 0x").append(Integer.toHexString(overlaySize)).append("\n");
			sb.append("    overlaynums ").append(nums.toString()).append("\n");
			sb.append("}\n");
		}
		return sb.toString().split("\n", -1);
	}

	private static int chooseOverlaySize(final int[] recEst, final int override)
	{
		if(override > 0)
			return override;
		int max = 0;
		if(recEst != null)
		{
			for(int i = 1; i < recEst.length; i++)
				max = Math.max(max, recEst[i]);
		}
		int size = Math.max(0x1000, (max * 5) / 4 + 0x200);
		size = ((size + 0x3f) / 0x40) * 0x40;
		return Math.min(size, 0x4800);
	}

	private static String[] genMakefile(final LnkManifest manifest, final List<String> objs,
		final String cfgName, final boolean grcPrebuilt, final String shimName)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("# Generated by GeoAsmConv v").append(D64Base.EMUTIL_VERSION)
			.append(" (Emutil) for ").append(manifest.appName).append(".\n");
		sb.append("# Build:  make    (produces ").append(manifest.appName).append(".cvt, a GEOS Convert v2.5 VLIR file)\n\n");
		sb.append("TARGET = geos-cbm\n");
		sb.append("APP    = ").append(manifest.appName).append("\n");
		sb.append("CA65  ?= ca65\n");
		sb.append("LD65  ?= ld65\n");
		sb.append("GRC65 ?= grc65\n\n");
		final String linkCfg = (cfgName != null) ? " -C $(CFG)" : " -t $(TARGET)";
		if(cfgName != null)
			sb.append("CFG    = ").append(cfgName).append("\n\n");
		sb.append("OBJS =");
		for(final String o : objs)
			sb.append(" ").append(o);
		sb.append("\n\n");
		sb.append("all: $(APP).cvt\n\n");
		if(grcPrebuilt)
		{
			sb.append("# $(APP).s header was generated by GeoAsmConv (grc65 + source fixes).\n");
			sb.append("$(APP).o: $(APP).s\n");
			sb.append("\t$(CA65) -t $(TARGET) -o $@ $(APP).s\n\n");
		}
		else
		{
			sb.append("$(APP).o: $(APP).grc\n");
			sb.append("\t$(GRC65) -t $(TARGET) $(APP).grc\n");
			sb.append("\t$(CA65) -t $(TARGET) -o $@ $(APP).s\n\n");
		}
		if(shimName != null)
			sb.append("%.o: %.s inc/" + shimName + "\n");
		else
			sb.append("%.o: %.s\n");
		sb.append("\t$(CA65) -t $(TARGET) -I inc -o $@ $<\n\n");
		sb.append("$(APP).cvt: $(APP).o $(OBJS)\n");
		sb.append("\t$(LD65)").append(linkCfg)
			.append(" -D __BACKBUFSIZE__=0 -D __STACKSIZE__=0 -o $@ -m $(APP).map $(APP).o $(OBJS)\n\n");
		sb.append("clean:\n");
		sb.append("\trm -f *.o $(APP).cvt $(APP).map $(APP).s $(APP).h\n");
		return sb.toString().split("\n", -1);
	}

	/** 
	 * Copy the cc65 geos-cbm linker config and shape its memory layout to match
	 * geoProgrammer.  A manifest ".ramsect &lt;addr&gt;" pins the main program's RAM
	 * (BSS) to a fixed address; the original linker then began every ".mod"
	 * overlay at VPRGbase = that address + the main program's RAM size, with the
	 * overlay's own RAM following its code.  This is modelled by a MAINRAM area
	 * at the ramsect address, a BSS segment loaded there, and a per-overlay
	 * OVLRAM&lt;n&gt; bss segment in its VLIR&lt;n&gt; area.  Returns the written config's
	 * file name, or null if the system config could not be located (the Makefile
	 * then falls back to "-t geos-cbm").
	 *
	 * @param outDir the output directory
	 * @param appName the application name used for the config file
	 * @param ramsect the manifest ".ramsect" address, or null
	 * @param startAddr the manifest ".psect" run base, or null
	 * @param overlayRecords the VLIR overlay record numbers (&gt; 0)
	 * @param ramOverlays the overlay records that declare their own .ramsect RAM
	 * @param cfgOverride an explicit cc65 linker config (--cfg / CC65_CFG), or null
	 */
	private static String writeLinkerConfig(final File outDir, final String appName,
		final String ramsect, final String startAddr,
		final Set<Integer> overlayRecords, final Set<Integer> ramOverlays,
		final Map<Integer, String> overlayPsect, final File cfgOverride) throws Exception
	{
		final File src = discoverCc65Cfg(cfgOverride);
		if(src == null)
			return null;
		final String rams = (ramsect == null) ? null : ramsect.trim();
		final boolean ramModel = (rams != null) && (rams.length() > 0);
		final Set<Integer> overlays = (overlayRecords == null)
			? new TreeSet<Integer>() : new TreeSet<Integer>(overlayRecords);
		final Set<Integer> ramsOvl = (ramOverlays == null)
			? new TreeSet<Integer>() : new TreeSet<Integer>(ramOverlays);
		// Some geoProgrammer apps append every overlay to the end of the resident
		// module's RAM (manifest ".psect VPRGbase" after each .mod).  Without a
		// ".ramsect" that symbol is the end of the main BSS, so the overlays must
		// start there rather than at the cc65 default (__HIMEM__).
		boolean symbolicBase = false;
		if(!ramModel && (overlayPsect != null))
		{
			for(final String ps : overlayPsect.values())
			{
				if(!ps.matches("\\$[0-9A-Fa-f]+") && !ps.matches("\\d+"))
				{
					symbolicBase = true;
					break;
				}
			}
		}
		final List<String> out = new ArrayList<String>();
		boolean memAdded = false;
		for(final String line : readFile(src))
		{
			String l = line;
			if((startAddr != null) && l.trim().startsWith("STARTADDRESS:"))
			{
				final int eq = l.indexOf('=');
				final int semi = l.lastIndexOf(';');
				if((eq > 0) && (semi > eq))
					l = l.substring(0, eq + 1) + " " + startAddr + " " + l.substring(semi);
			}
			if(ramModel && l.trim().startsWith("__OVERLAYADDR__:"))
				l = l.replace("__HIMEM__ - __OVERLAYSIZE__", rams + " + __BSS_SIZE__");
			else
			if(symbolicBase && l.trim().startsWith("__OVERLAYADDR__:"))
				l = l.replace("__HIMEM__ - __OVERLAYSIZE__", "__VLIR0_LAST__");
			if(ramModel && l.trim().startsWith("BSS:") && (l.indexOf("type") >= 0))
				l = l.replace("load = VLIR0", "load = MAINRAM");
			if((ramModel || symbolicBase) && l.trim().startsWith("VLIR0:"))
				l = l.replace("size = __STACKADDR__ - %S", "size = __HIMEM__ - %S");
			if((ramModel || symbolicBase) && (overlays.size() > 0) && l.trim().startsWith("VLIR")
				&& (l.indexOf("__OVERLAYSIZE__") >= 0))
				l = l.replace("__OVERLAYSIZE__", "__HIMEM__ - __OVERLAYADDR__");
			// a per-record ".psect <literal>" overrides the overlay's run base
			if((overlayPsect != null) && !overlayPsect.isEmpty() && l.trim().startsWith("VLIR"))
			{
				final Matcher vm = Pattern.compile("^VLIR(\\d+):").matcher(l.trim());
				if(vm.find())
				{
					final String ps = overlayPsect.get(Integer.valueOf(Integer.parseInt(vm.group(1))));
					if((ps != null) && (ps.matches("\\$[0-9A-Fa-f]+") || ps.matches("\\d+")))
						l = l.replace("start = __OVERLAYADDR__", "start = " + ps);
				}
			}
			out.add(l);
			if(ramModel && !memAdded && l.trim().startsWith("VLIR0:"))
			{
				out.add("    MAINRAM: start = " + rams + ",             size = __HIMEM__ - " + rams + ";");
				memAdded = true;
			}
			if(l.trim().startsWith("OVERLAY"))
			{
				final Matcher om = Pattern.compile("^OVERLAY(\\d+):").matcher(l.trim());
				if(om.find() && ramsOvl.contains(Integer.valueOf(om.group(1))))
				{
					final int n = Integer.parseInt(om.group(1));
					// overlay RAM always follows its overlay's code in that
					// overlay's own VLIR area (the record length patch subtracts it)
					out.add("    OVLRAM" + n + ": type = bss, load = VLIR" + n + ",  define = yes;");
				}
			}
		}
		final String name = appName + ".cfg";
		writeFile(new File(outDir, name), out);
		return name;
	}

	/** 
	 * Rough byte-volume estimate for a converted module (drives overlay sizing).
	 *
	 * @param lines the converted module lines
	 */
	private static int estimateBytes(final List<String> lines)
	{
		int total = 0;
		boolean skip = false;
		for(final String line : lines)
		{
			final String t = stripComment(line).trim();
			if(t.length() == 0 || t.startsWith(";"))
				continue;
			if(t.startsWith(".segment"))
			{
				skip = t.contains("BSS") || t.contains("ZEROPAGE");
				continue;
			}
			if(skip)
				continue;
			if(t.startsWith(".byte") || t.startsWith(".dbyt"))
			{
				final Matcher om = OPERAND_MATCH.matcher(t);
				if(om.find())
				{
					final String oper = om.group(1).trim();
					if(oper.startsWith("\""))
						total += stringLen(oper) ;
					else
						total += splitArgs(oper).size();
				}
			}
			else
			if(t.startsWith(".word") || t.startsWith(".addr"))
			{
				final Matcher om = OPERAND_MATCH.matcher(t);
				if(om.find())
					total += 2 * Math.max(1, splitArgs(om.group(1).trim()).size());
			}
			else
			if(t.startsWith(".dword") || t.startsWith(".faraddr"))
			{
				final Matcher om = OPERAND_MATCH.matcher(t);
				if(om.find())
					total += 4 * Math.max(1, splitArgs(om.group(1).trim()).size());
			}
			else
			if(t.startsWith(".res"))
			{
				final Matcher rm = Pattern.compile("(?i)^\\.res\\s+\\(?(\\$?[0-9a-fA-F]+)").matcher(t);
				if(rm.find())
				{
					try
					{
						total += rm.group(1).startsWith("$")
							? Integer.parseInt(rm.group(1).substring(1), 16)
							: Integer.parseInt(rm.group(1));
					}
					catch(final Exception e)
					{
						total += 8;
					}
				}
			}
			else
			if(!t.startsWith(".if") && !t.startsWith(".else")
			&& !t.startsWith(".endif") && !t.startsWith(".macro")
			&& !t.startsWith(".endmacro") && !t.startsWith(".include")
			&& !t.startsWith(".macpack") && !t.startsWith(".scope")
			&& !t.startsWith(".endscope"))
				total += 3;
		}
		return total;
	}

	private static int stringLen(final String oper)
	{
		int n = 0;
		for(final String a : splitArgs(oper))
		{
			final String aa = a.trim();
			if(aa.startsWith("\""))
				n += aa.length() - 2;
			else
				n += 1;
		}
		return n;
	}

	private static void writeFile(final File f, final String[] lines) throws IOException
	{
		final FileOutputStream fo = new FileOutputStream(f);
		try
		{
			for(final String line : lines)
			{
				fo.write(line.getBytes("ISO-8859-1"));
				fo.write('\n');
			}
		}
		finally
		{
			try
			{
				fo.close();
			}
			catch(final IOException e)
			{
			}
		}
	}

	private static void writeFile(final File f, final List<String> lines) throws IOException
	{
		writeFile(f, lines.toArray(new String[0]));
	}

	/** 
	 * GeoProgrammer stored two header fields that grc65 cannot express: the
	 * author string keeps its trailing space padding, and the byte after the
	 * class/version ("40/80 column flag") is explicit source data.  After
	 * grc65 generates the header assembly, rewrite those bytes from the H doc
	 * so the emitted header matches the original assembler.
	 *
	 * @param sFile the generated header assembly file
	 * @param hinfo the parsed H-doc header fields (name, version, author, info)
	 * @param colFlag the 40/80-column flag byte, or -1 if absent
	 * @param htype the GEOS file type byte from the H doc, or -1
	 * @param haddrs the H-doc load/end/init expressions, or null
	 */
	private static void patchGrcHeader(final File sFile, final String[] hinfo, final int colFlag,
		final int htype, final String[] haddrs) throws IOException
	{
		if((hinfo == null) || (hinfo[2] == null))
			return;
		final String author = hinfo[2];
		final List<String> lines = readFile(sFile);

		// grc65 hardcodes the APPLICATION file type and derives load/end/init
		// from the linker; the H doc is authoritative for all of them.
		if(htype >= 0)
		{
			boolean inDir = false;
			boolean inFin = false;
			for(int i = 0; i < lines.size(); i++)
			{
				final String t = lines.get(i).trim();
				if(t.startsWith(".segment"))
				{
					inDir = t.contains("DIRENTRY");
					inFin = t.contains("FILEINFO");
				}
				if(inDir && t.matches("\\.byte\\s+6\\s*"))
					lines.set(i, "\t.byte " + htype);
				else
				if(inFin)
				{
					final Matcher fm = Pattern.compile(
						"\\.byte\\s+131\\s*,\\s*6\\s*,\\s*(\\d+)\\s*").matcher(t);
					if(fm.matches())
						lines.set(i, "\t.byte 131, " + htype + ", " + fm.group(1));
				}
			}
		}
		if((haddrs != null) && (haddrs[1] != null) && (haddrs[2] != null))
		{
			final String end = mapHeaderAddr(haddrs[1], false);
			final String init = mapHeaderAddr(haddrs[2], true);
			final Set<String> needImport = new LinkedHashSet<String>();
			for(final String e : new String[] { end, init })
			{
				if(e.matches("[A-Za-z_][A-Za-z0-9_]*") 
				&& !e.equals("__VLIR0_START__") 
				&& !e.equals("__STARTUP_RUN__"))
					needImport.add(e);
			}
			for(int i = 0; i < lines.size(); i++)
			{
				final String t = lines.get(i).trim();
				if(t.startsWith(".word") && (t.indexOf("__VLIR0_START__") >= 0)
				&& (t.indexOf("__STARTUP_RUN__") >= 0))
				{
					lines.set(i, "\t.word __VLIR0_START__, " + end + ", " + init);
					break;
				}
			}
			if(needImport.size() > 0)
			{
				for(int i = 0; i < lines.size(); i++)
				{
					final String t = lines.get(i).trim();
					if(t.startsWith(".import") && (t.indexOf("__VLIR0_START__") >= 0))
					{
						lines.set(i, lines.get(i).trim() + ", "
							+ String.join(", ", needImport));
						break;
					}
				}
			}
		}

		boolean inFileinfo = false;
		for(int i = 0; i < lines.size(); i++)
		{
			final String t = lines.get(i).trim();
			if(t.startsWith(".segment"))
				inFileinfo = t.contains("FILEINFO");
			if(!inFileinfo)
				continue;
			if(t.matches("\\.byte\\s+0\\s*,\\s*0\\s*,\\s*0"))
			{
				// flag byte is the single ".byte <n>" right after
				if(((i + 1) < lines.size()) && (colFlag >= 0)
					&& lines.get(i + 1).trim().matches("\\.byte\\s+\\d+"))
					lines.set(i + 1, "\t.byte " + colFlag);
				// author is the next quoted .byte after the flag
				for(int k = i + 1; k < lines.size(); k++)
				{
					final String at = lines.get(k).trim();
					if(at.startsWith(".byte \"") && (author.length() > 0))
						lines.set(k, "\t.byte \"" + author + "\"");
					if(at.startsWith(".res"))
					{
						final Matcher rm = Pattern.compile(
							"\\.res\\s*\\(\\s*63\\s*-\\s*\\d+\\s*\\)").matcher(lines.get(k));
						if(rm.find())
							lines.set(k, lines.get(k).replace(rm.group(0),
								".res  (63 - " + (author.length() + 1) + ")"));
						// grc65 collapses an all-whitespace info field to a single
						// space; restore the exact text from the H doc, keeping the
						// ",13" CR separators between wrapped lines.
						if((hinfo.length > 3) && (hinfo[3] != null) && (hinfo[3].length() > 1))
						{
							for(int m = k + 1; m < lines.size(); m++)
							{
								final String mt = lines.get(m).trim();
								if(mt.startsWith(".byte \""))
								{
									final List<String> repl = new ArrayList<String>();
									final String body = hinfo[3];
									final boolean endsCr = body.endsWith("\r");
									final String[] parts = body.split("\r", -1);
									for(int p = 0; p < parts.length; p++)
									{
										if((p == parts.length - 1) && (parts[p].length() == 0))
											break;
										final boolean last = (p == parts.length - 1);
										repl.add("\t.byte \"" + parts[p].replace("\\", "\\\\")
											+ "\"" + ((!last || endsCr) ? ",13" : ""));
									}
									lines.remove(m);
									lines.addAll(m, repl);
									break;
								}
								if(mt.startsWith(".segment"))
									break;
							}
						}
						break;
					}
				}
				break;
			}
		}
		writeFile(sFile, lines);
	}

	/** 
	 * Rewrite the VLIR record-length expressions grc65 emitted so they reflect
	 * the geoProgrammer RAM layout.  With the ".ramsect" RAM model the main
	 * program's BSS no longer lives in VLIR0, so record 0's "- __BSS_SIZE__"
	 * term must be dropped; and an overlay's own RAM is a bss segment inside its
	 * VLIR area, so that overlay's record length must subtract the RAM size.
	 *
	 * @param sFile the generated header assembly file
	 * @param ramModel true when the ramsect RAM model is in use
	 * @param ramOverlays overlay records that declare their own .ramsect RAM
	 */
	private static void patchGrcRecords(final File sFile, final boolean ramModel,
		final Set<Integer> ramOverlays) throws IOException
	{
		final List<String> lines = readFile(sFile);

		// grc65 emits one [numBlocks, extra] pair per record and lets the rest of
		// the 254-byte (127-entry) VLIR index sector zero-fill.  geoProgrammer
		// instead filled every unused slot with the null marker 00 FF.
		final int recSeg = indexOfSegment(lines, "RECORDS");
		if(recSeg >= 0)
		{
			int recEnd = lines.size();
			for(int i = recSeg + 1; i < lines.size(); i++)
			{
				if(lines.get(i).trim().startsWith(".segment"))
				{
					recEnd = i;
					break;
				}
			}
			int activeBytes = 0;
			int lastByteLine = -1;
			for(int i = recSeg + 1; i < recEnd; i++)
			{
				final String t = lines.get(i).trim();
				if(t.startsWith(".byte") && (t.indexOf("__VLIR") >= 0))
				{
					activeBytes++;
					lastByteLine = i;
				}
			}
			final int filler = (activeBytes > 0) ? ((254 - activeBytes) / 2) : 0;
			if(filler > 0)
			{
				final List<String> pad = new ArrayList<String>();
				for(int k = 0; k < filler; k++)
					pad.add("\t.byte 0, $ff");
				lines.addAll(lastByteLine + 1, pad);
			}
		}

		if(ramModel)
		{
			for(int i = 0; i < lines.size(); i++)
			{
				final String l = lines.get(i);
				if(l.contains("__VLIR0_LAST__") && l.contains("__BSS_SIZE__"))
					lines.set(i, l.replace(" - __BSS_SIZE__", ""));
			}
		}
		// every overlay that declares its own RAM puts that RAM in its VLIR
		// area, so the recorded record length must exclude it (only the code
		// is loaded when the overlay is brought in).
		for(final Integer n : ramOverlays)
		{
			final String from = "__VLIR" + n + "_LAST__ - __VLIR" + n + "_START__";
			final String to = from + " - __OVLRAM" + n + "_SIZE__";
			boolean patched = false;
			for(int i = 0; i < lines.size(); i++)
			{
				final String l = lines.get(i);
				if(l.contains(from))
				{
					lines.set(i, l.replace(from, to));
					patched = true;
				}
			}
			if(patched)
			{
				for(int i = 0; i < lines.size(); i++)
				{
					final String t = lines.get(i).trim();
					if(t.startsWith(".import") && t.contains("__VLIR" + n + "_LAST__")
						&& (t.indexOf("__OVLRAM" + n + "_SIZE__") < 0))
					{
						lines.set(i, lines.get(i).trim() + ", __OVLRAM" + n + "_SIZE__");
						break;
					}
				}
			}
		}
		writeFile(sFile, lines);
	}

	/** 
	 * Find the line index of the .segment directive naming the given segment,
	 * or -1 if absent.
	 *
	 * @param lines the assembly lines
	 * @param name the segment name to find
	 */
	private static int indexOfSegment(final List<String> lines, final String name)
	{
		for(int i = 0; i < lines.size(); i++)
		{
			final String t = lines.get(i).trim();
			if(t.startsWith(".segment") && (t.indexOf(name) >= 0))
				return i;
		}
		return -1;
	}

	/** 
	 * Map an H-doc header address expression to a linker-safe one.  "ProgStart"
	 * is geoProgrammer's program-base pseudo-symbol; a zero init address means
	 * "use the standard $0400 entry".
	 *
	 * @param expr the H-doc expression
	 * @param isInit true for the init word, false for the end word
	 * @return the expression to emit
	 */
	private static String mapHeaderAddr(final String expr, final boolean isInit)
	{
		final String e = expr.trim();
		if(e.equalsIgnoreCase("ProgStart"))
			return "__VLIR0_START__";
		if(isInit && (e.equals("0") || e.equals("$0") || e.equals("$0000")
			|| e.equals("$000")))
			return "$0400";
		return e;
	}

	/** 
	 * The byte following the H header's class/version string (".byte
	 * \"name version\",0,0,0,64") is the geoProgrammer 40/80-column flag.
	 *
	 * @param hdoc the H header document
	 */
	private static int headerColumnFlag(final File hdoc) throws Exception
	{
		final GeoRWriter doc = new GeoRWriter(hdoc, false);
		for(final String line : extractLines(doc))
		{
			final String code = stripComment(line);
			if((code.indexOf(".byte") < 0) || (code.indexOf('"') < 0))
				continue;
			final int q1 = code.indexOf('"');
			final int q2 = code.indexOf('"', q1 + 1);
			if(q2 <= q1)
				continue;
			final Matcher m = Pattern.compile("(\\d+)\\s*$").matcher(code.substring(q2 + 1).trim());
			if(m.find())
				return Integer.parseInt(m.group(1));
		}
		return -1;
	}

	/** 
	 * Locate the cc65 linker config for GEOS CBM targets.  Lookup order: an
	 * explicit --cfg/CC65_CFG override, a config under CC65_HOME, then the
	 * cfg/ directory beside the target path reported by the cc65 driver
	 * ("cl65 --print-target-path").  Returns null when no config is found, in
	 * which case the generated Makefile falls back to ld65's "-t geos-cbm".
	 *
	 * @param override an explicit config file, or null
	 */
	private static File discoverCc65Cfg(final File override) throws IOException
	{
		if(override != null)
		{
			if(override.isFile())
				return override;
			throw new IOException("--cfg file not found: " + override.getPath());
		}
		final List<File> cands = new ArrayList<File>();
		final String cfgEnv = System.getenv(CFG_ENV);
		if((cfgEnv != null) && (cfgEnv.trim().length() > 0))
		{
			final File env = new File(cfgEnv.trim());
			if(env.isFile())
				return env;
			System.err.println("Warning: " + CFG_ENV + " file not found: " + env.getPath());
		}
		final String home = System.getenv(CC65_HOME_ENV);
		if(home != null)
		{
			cands.add(new File(home, "cfg/" + GEOS_CFG_FILE));
			cands.add(new File(new File(home, "share/cc65"), "cfg/" + GEOS_CFG_FILE));
		}
		// Ask the toolchain where its target files live; the cfg/ directory is
		// its sibling.  This avoids hardcoding distro-specific install paths.
		for(final String tool : new String[] { "cl65", "cc65" })
		{
			final String targetPath = runForOutput(tool, "--print-target-path");
			if((targetPath != null) && (targetPath.trim().length() > 0))
			{
				final File targetDir = new File(targetPath.trim());
				final File root = targetDir.getParentFile();
				if(root != null)
					cands.add(new File(new File(root, "cfg"), GEOS_CFG_FILE));
			}
		}
		for(final File f : cands)
		{
			if((f != null) && f.isFile())
				return f;
		}
		return null;
	}

	/** 
	 * Run an external command and return its standard output, or null when the
	 * command cannot be started or exits non-zero.
	 *
	 * @param cmd the command and arguments
	 */
	private static String runForOutput(final String... cmd)
	{
		Process p = null;
		try
		{
			final ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(true);
			p = pb.start();
			final java.io.InputStream in = p.getInputStream();
			final StringBuilder sb = new StringBuilder();
			int c;
			while((c = in.read()) >= 0)
				sb.append((char)c);
			return (p.waitFor() == 0) ? sb.toString() : null;
		}
		catch(final Exception e)
		{
			return null;
		}
		finally
		{
			if(p != null)
				p.destroy();
		}
	}

	/** 
	 * Run grc65 on the generated .grc in outDir; returns true if it produced
	 * the .s header assembly.
	 *
	 * @param outDir the directory holding the .grc file
	 * @param appName the application name (grc file basename)
	 */
	private static boolean runGrc65(final File outDir, final String appName)
	{
		Process p = null;
		try
		{
			final ProcessBuilder pb = new ProcessBuilder("grc65", "-t", "geos-cbm", appName + ".grc");
			pb.directory(outDir);
			pb.redirectErrorStream(true);
			p = pb.start();
			final java.io.InputStream in = p.getInputStream();
			while(in.read() >= 0)
			{
			}
			return p.waitFor() == 0;
		}
		catch(final Exception e)
		{
			return false;
		}
		finally
		{
			if(p != null)
				p.destroy();
		}
	}

	private static void writeFileBytes(final File f, final byte[] data) throws IOException
	{
		java.io.FileOutputStream out = null;
		try
		{
			out = new java.io.FileOutputStream(f);
			out.write(data);
		}
		finally
		{
			if(out != null)
			{
				try
				{
					out.close();
				}
				catch(final IOException e)
				{
				}
			}
		}
	}

	/** 
	 * Decode the app icon embedded in an H header doc.  The icon is a geoWrite
	 * graphic whose payload uses the GEOS bitmap run-length form: a control
	 * byte C of 0 ends the stream, C<0x80 repeats the next byte C times, and
	 * C>=0x80 is a literal run of C&amp;0x7f bytes.  The graphic's scrap opens
	 * with a 3-byte width/height header which is skipped.
	 *
	 * @param hdoc the H header document
	 */
	private static byte[] decodeHeaderIcon(final File hdoc) throws Exception
	{
		final GeoRWriter doc = new GeoRWriter(hdoc, false);
		for(int p = 0; p < doc.getNumPages(); p++)
		{
			for(final GeoRWriter.ClipRef pic : doc.getPagePictures(p))
			{
				final byte[] scrap = doc.getRecordData(pic.record);
				if((scrap != null) && (scrap.length > 3))
				{
					final java.io.ByteArrayOutputStream bout = new java.io.ByteArrayOutputStream();
					int i = 3;
					while(i < scrap.length)
					{
						final int c = scrap[i++] & 0xff;
						if(c == 0)
							break;
						if((c & 0x80) != 0)
						{
							final int n = c & 0x7f;
							for(int k = 0; (k < n) && (i < scrap.length); k++)
								bout.write(scrap[i++] & 0xff);
						}
						else
						{
							if(i >= scrap.length)
								break;
							final int v = scrap[i++] & 0xff;
							for(int k = 0; k < c; k++)
								bout.write(v);
						}
					}
					final byte[] d = bout.toByteArray();
					if(d.length >= 63)
						return d;
				}
			}
		}
		return null;
	}

	private static List<String> readFile(final File f) throws IOException
	{
		final List<String> lines = new ArrayList<String>();
		BufferedReader in = null;
		try
		{
			in = new BufferedReader(new InputStreamReader(
				new FileInputStream(f), "ISO-8859-1"));
			String line = null;
			while((line = in.readLine()) != null)
				lines.add(line);
		}
		finally
		{
			if(in != null)
			{
				try
				{
					in.close();
				}
				catch(final IOException e)
				{
				}
			}
		}
		return lines;
	}

	/** 
	 * Collect defined symbols (labels/equates/macros) and referenced words from converted source.
	 *
	 * @param lines the converted source lines
	 * @param defs the set to add defined symbols to
	 * @param refs the set to add referenced words to
	 */
	private static void collectSymbols(final List<String> lines, final Set<String> defs,
		final Set<String> refs)
	{
		final Pattern labelP = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*):");
		final Pattern equP = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*(:=|=)\\s");
		final Pattern macroP = Pattern.compile("(?i)^\\.macro\\s+([A-Za-z_][A-Za-z0-9_]*)");
		final Pattern tokenP = Pattern.compile("(?<![@.])\\b[A-Za-z_][A-Za-z0-9_]*");
		for(final String line : lines)
		{
			final String t = line.trim();
			if(t.length() == 0 || t.startsWith(";"))
				continue;
			final String noCom = stripComment(t);
			if(noCom.toLowerCase().startsWith(".include"))
				continue;   // include path names are not symbol references
			final Matcher lm = labelP.matcher(noCom);
			if(lm.find())
				defs.add(lm.group(1));   // case preserved: geoAssembler was case-significant
			final Matcher em = equP.matcher(noCom);
			if(em.find())
				defs.add(em.group(1));
			final Matcher mm = macroP.matcher(noCom);
			if(mm.find())
				defs.add(mm.group(1));
			final Matcher tm = tokenP.matcher(noCom);
			while(tm.find())
			{
				final String tok = tm.group();
				if(tok.length() > 0)
					refs.add(tok);   // case preserved
			}
		}
	}

	/** 
	 * Repair case-flipped symbol references (doc-extraction damage): rewrite
	 * any reference whose case-exact spelling is undefined but which matches
	 * a definition case-insensitively, to that definition's spelling.  Macro
	 * definition regions are skipped (parameter names are private).  Rewrites
	 * {@code lines} in place when anything changes; returns the number of
	 * rewrites.
	 *
	 * @param lines the lines to repair in place
	 * @param defs the known symbol definitions
	 */
	private static int repairCaseLines(final List<String> lines, final Set<String> defs)
	{
		final Map<String, String> lowMap = new HashMap<String, String>();
		for(final String d : defs)
		{
			final String low = d.toLowerCase();
			final String prev = lowMap.put(low, d);
			if((prev != null) && !prev.equals(d))
				lowMap.put(low, "?");   // ambiguous: two defs differ only by case
		}
		final Pattern tokP = Pattern.compile("(?<![@.$A-Za-z0-9_])([A-Za-z_][A-Za-z0-9_]*)");
		int repaired = 0;
		int macroDepth = 0;
		boolean changed = false;
		final List<String> out = new ArrayList<String>(lines.size());
		for(final String raw : lines)
		{
			final String t = raw.trim();
			if((macroDepth == 0)
				&& (t.length() == 0 || t.startsWith(";")
					|| t.toLowerCase().startsWith(".include")))
			{
				out.add(raw);
				continue;
			}
			if(t.toLowerCase().startsWith(".macro"))
			{
				macroDepth++;
				out.add(raw);
				continue;
			}
			if(macroDepth > 0)
			{
				if(t.toLowerCase().startsWith(".endmacro") || t.toLowerCase().startsWith(".endm"))
					macroDepth--;
				out.add(raw);   // macro body: labels/params are private
				continue;
			}
			final int ci = findCommentStart(raw);
			final String codePart = (ci < 0) ? raw : raw.substring(0, ci);
			final String comment  = (ci < 0) ? "" : raw.substring(ci);
			final List<String> strings = new ArrayList<String>();
			final String code = protectStrings(codePart, strings);
			final Matcher tm = tokP.matcher(code);
			final StringBuffer sb = new StringBuffer();
			while(tm.find())
			{
				final String tok = tm.group(1);
				String rep = tok;
				if(!defs.contains(tok))
				{
					final String cand = lowMap.get(tok.toLowerCase());
					if((cand != null) && !cand.equals("?") && !cand.equals(tok))
					{
						rep = cand;
						repaired++;
					}
				}
				tm.appendReplacement(sb, Matcher.quoteReplacement(rep));
			}
			tm.appendTail(sb);
			final String fixed = restoreStrings(sb.toString(), strings) + comment;
			if(!fixed.equals(raw))
				changed = true;
			out.add(fixed);
		}
		if(changed)
		{
			lines.clear();
			lines.addAll(out);
		}
		return repaired;
	}

	private static int repairCase(final List<String> lines, final Set<String> defs, final File f)
		throws IOException
	{
		final int n = repairCaseLines(lines, defs);
		if(n > 0)
			writeFile(f, lines);
		return n;
	}

	private static String joinStringsComma(final List<String> strings)
	{
		final StringBuilder sb = new StringBuilder();
		for(int i = 0; i < strings.size(); i++)
		{
			if(i > 0)
				sb.append(", ");
			sb.append(strings.get(i));
		}
		return sb.toString();
	}

	private static int findCommentStart(final String line)
	{
		boolean inStr = false;
		for(int i = 0; i < line.length(); i++)
		{
			final char c = line.charAt(i);
			if(c == '"')
				inStr = !inStr;
			else
			if((c == ';') && (!inStr))
				return i;
		}
		return -1;
	}

	private static boolean isConditionalDirective(final String dnameRaw)
	{
		return dnameRaw.equals(".if")
			|| dnameRaw.equals(".elseif")
			|| dnameRaw.equals(".ifdef")
			|| dnameRaw.equals(".ifndef")
			|| dnameRaw.equals(".ifconst")
			|| dnameRaw.equals(".ifref")
			|| dnameRaw.equals(".ifblank")
			|| dnameRaw.equals(".while");
	}

	/** 
	 * Sanitize a source-doc fragment line: page damage can fuse an orphan
	 * token onto a real instruction ("uy lda source+0") or leave a bare
	 * garbage token ("UUUUU").  Returns the (possibly cleaned) code, or null
	 * when the whole line is garbage and the caller should comment it out.
	 *
	 * @param codePart the code portion of the line
	 * @param trimmed the trimmed code portion
	 * @param res the conversion result to record warnings on
	 * @param st the current conversion state
	 */
	private static String sanitizeFragment(final String codePart, final String trimmed,
		final ConvertResult res, final State st)
	{
		// Macro bodies may contain pseudo-mnemonics/params that are not real
		// ca65 lines; never classify them there.
		if(st.macroDepth > 0)
			return codePart;
		final String stripped = trimmed.trim();
		// equate definitions "NAME = value" / "NAME == value" are valid ca65 and
		// are tracked separately (handleEquateLine); never classify them as junk.
		if(stripped.matches("^[A-Za-z_][A-Za-z0-9_$]*\\s*(==|=).*"))
			return codePart;
		final String[] parts = stripped.split("\\s+");
		final String lead = parts[0];
		// real opcodes always pass... but a bare opcode with no operand ("jsr" by
		// itself) is a source-doc fragment, not a valid instruction.
		if(OPCODES.contains(lead.toLowerCase()))
		{
			if((parts.length == 1) && !NO_OPERAND_OPCODES.contains(lead.toLowerCase()))
			{
				res.warnings.add("'" + stripped
					+ "' is a bare opcode with no operand (source-doc fragment/prose); commented out");
				return null;
			}
			return codePart;
		}
		// unexpanded macro calls always pass
		if(st.macroNames.contains(lead.toLowerCase()))
			return codePart;
		// numeric local "@NN" / "@NN:" and geoAssembler "NN$"/"NN$:" lines
		if(lead.matches("@[0-9]+:?") || lead.matches("[0-9]+\\$:?"))
			return codePart;
		// named ca65 local "@name" / "@name:"
		if(lead.matches("@[A-Za-z_][A-Za-z0-9_@$]*:?"))
			return codePart;
		// a label token (possibly ':'/'::' suffix, possibly glued to an opcode):
		//   "Label:" / "Label::" / "Label:lda" / "CloseFil::  lda ..."
		String labelBase = lead;
		String after = "";
		final int lc = lead.indexOf(':');
		boolean hadColon = false;
		if(lc >= 0)
		{
			labelBase = lead.substring(0, lc);
			after = lead.substring(lc + 1);
			while(after.startsWith(":"))
				after = after.substring(1);
			hadColon = true;
		}
		if(hadColon)
		{
			// "Label:" alone, "Label:opcode" glued, or "Label: .directive":
			// a real label definition.  Anything else ("by: Bo Zimmerma" prose)
			// is not valid ca65 assembly.
			if(isLabelDef(stripped, st)
			|| labelBase.matches("@[0-9]+")
			|| labelBase.matches("[0-9]+\\$"))
				return codePart;
			res.warnings.add("'" + stripped
				+ "' is not valid ca65 assembly (source-doc fragment/prose); commented out");
			return null;
		}
		// plain identifier without a colon
		final boolean ident = lead.matches("[A-Za-z_][A-Za-z0-9_$]*");
		if(ident)
		{
			if(parts.length >= 2)
			{
				// orphan token before an opcode: "uy lda source+0" (page damage)
				if(OPCODES.contains(parts[1].toLowerCase()))
				{
					res.warnings.add("orphan token '" + lead + "' before an opcode removed"
						+ " (page damage): '" + stripped + "'");
					return codePart.replaceFirst("^\\s*" + Pattern.quote(lead) + "\\s+", "");
				}
			}
			res.warnings.add("'" + stripped
				+ "' is a bare symbol with no ':' (ca65 would reject it; source-doc fragment?)");
			return null;
		}
		// anything else -- prose, page-damage junk, fragment tails, or tokens
		// with stray punctuation -- is not a valid ca65 line; comment it out.
		res.warnings.add("'" + stripped
			+ "' is not valid ca65 assembly (source-doc fragment/prose); commented out");
		return null;
	}

	private static boolean handleEquateLine(final String trimmed, final String comment,
		final State st, final ConvertResult res, final ConvertOptions options)
	{
		final Matcher em = EQUATE_PATTERN.matcher(trimmed);
		if(!em.find())
			return false;
		final String name = em.group(1);
		final String rhs  = em.group(3).trim();
		if(rhs.length() == 0)
		{
			res.lines.add("; " + trimmed.trim()
				+ " [empty value in source doc; skipped]"
				+ ((comment.length() > 0) ? "\t" + comment : ""));
			res.warnings.add("'" + trimmed.trim()
				+ "' has an empty value and was commented out");
			return true;
		}
		// an equate value is a single constant expression; two whitespace tokens
		// ("LSy = picHdb R1H,#195") betray a fused source-doc fragment where the
		// next line's text was glued onto a real equate.  Ca65 can't parse it, and
		// guessing at the split is unsafe, so comment the whole line out.  A second
		// token that begins with an operator/paren (e.g. "(A) + (B)") is a real
		// expression and is left alone.
		if(rhs.matches("^\\S+\\s+(?![+\\-*/)(<>=~^|&%,])\\S+.*"))
		{
			// "LSy = picHdb R1H,#195": a real equate to a picture dimension
			// ("picW"/"picH") with the next statement ("db R1H,#195") glued onto
			// it.  Split and re-dispatch both halves.
			if(rhs.startsWith("picW") || rhs.startsWith("picH"))
			{
				final String rest = rhs.substring(4).trim();
				if(rest.length() > 0)
				{
					translateLine(name + " = " + rhs.substring(0, 4), st, res, options);
					translateLine(rest, st, res, options);
					return true;
				}
			}
			res.lines.add("; " + trimmed.trim()
				+ " [fused source-doc fragment; invalid equate value]"
				+ ((comment.length() > 0) ? "\t" + comment : ""));
			res.warnings.add("'" + trimmed.trim()
				+ "' equate value is a fused source-doc fragment and was commented out");
			return true;
		}
		String tname = name;
		if(tname.length() > 8)   // geoAssembler 8-significant-char rule
			tname = tname.substring(0, 8);
		if(st.definedLabels.contains(tname))
		{
			res.lines.add("; " + trimmed.trim()
				+ " [equate clashes with a label defined later; skipped]"
				+ ((comment.length() > 0) ? "\t" + comment : ""));
			res.warnings.add("'" + trimmed.trim()
				+ "' equate clashes with a later label definition and was skipped");
			return true;
		}
		final String prev = st.equates.put(name, rhs);
		if(prev == null)
			return false;
		if(prev.equals(rhs))
		{
			st.dedupCount++;
			return true; // identical page-repeat; drop
		}
		res.warnings.add("'" + name + "' redefined with a different value (ca65 rejects re-definition)");
		return false;
	}

	private static String rewriteCode(final String code, final boolean allowDoubleEq,
		final State st)
	{
		return rewriteCode(code, allowDoubleEq, true, st);
	}

	private static String rewriteCode(final String code, final boolean allowDoubleEq,
		final boolean rewriteLocals, final State st)
	{
		final List<String> strings = new ArrayList<String>();
		String out = protectStrings(code, strings);
		out = TRUNC8_PATTERN.matcher(out).replaceAll("$1");
		out = out.replace('[', '<');
		out = out.replace(']', '>');
		// data-directive operands: strip page-damage "w" tokens glued to numeric
		// values ("$24,0,0,0,0,0,0w,#0") -- geoAssembler has no 'w' suffix.
		if(!rewriteLocals)
			out = stripDataJunk(out);
		// ca65 mis-evaluates char-literal arithmetic ("sbc #'a' - 'A'" yields a
		// range error rather than 32); a bare #'X' immediate is fine.  Reduce any
		// single-char literal embedded in an expression to its numeric code.
		out = expandCharLiterals(out);
		// "picW"/"picH" are the pixel dimensions of the most recently embedded
		// clip-art image.  Substitute their current values so the picture's
		// follow-on equates (.e.g "FBOXdwid =picW") become plain constants and
		// a module with several pictures never re-defines a ca65 symbol.
		if(st.curPicW != null)
			out = out.replaceAll("(?<![A-Za-z0-9_$])picW(?![A-Za-z0-9_$])", st.curPicW.toString());
		if(st.curPicH != null)
			out = out.replaceAll("(?<![A-Za-z0-9_$])picH(?![A-Za-z0-9_$])", st.curPicH.toString());
		// geoAssembler "Label::" (global/entry) is rejected by ca65 even at top
		// level ("No such scope: 'Label'"); single-file output is one flat module.
		out = out.replaceAll("(?m)^([\\t ]*)([A-Za-z_][A-Za-z0-9_$]*)::", "$1$2:");
		out = NUMERIC_LOCAL_DEF_PATTERN.matcher(out).replaceAll("$1@$2:");
		out = NUMERIC_LOCAL_PATTERN.matcher(out).replaceAll("@$1");
		// geoAssembler references a numeric local "NN$:" as "$NN" (or with a digit
		// suffix).  Rewrite to the ca65 local only when that number is defined as a
		// local in this module, and never inside data directives where "$NN" is hex.
		// Operands of data directives are rewritten without local mapping (their
		// "$NN" is a value, not a local reference).
		if(rewriteLocals)
			out = rewriteLocalRefs(out, st);
		if(allowDoubleEq)
			out = DOUBLE_EQ_PATTERN.matcher(out).replaceAll(":=");
		return restoreStrings(out, strings);
	}

	/** 
	 * ca65 mis-evaluates char-literal arithmetic: "sbc #'a' - 'A'" reports a
	 * Range error (-128) instead of 32, under any parenthesization, while a bare
	 * "#'X'" immediate is valid.  When the operand is not exactly one char
	 * literal, replace every single-char literal with its numeric code so ca65
	 * sees plain numbers.  "('AB')" two-char literals are left untouched.
	 *
	 * @param code the source text to rewrite
	 */
	private static String expandCharLiterals(final String code)
	{
		final String trimmed = code.trim();
		if(trimmed.matches("^#?'[^']'$"))
			return code;
		final StringBuffer sb = new StringBuffer();
		final Matcher m = Pattern.compile("'([^']')").matcher(code);
		while(m.find())
		{
			m.appendReplacement(sb, Matcher.quoteReplacement("$"
				+ Integer.toHexString(m.group(1).charAt(0)).toUpperCase()));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	/** 
	 * In data directives, remove page-damage "w" tokens that geoAssembler docs
	 * occasionally carry (a neighboring "word" marker overlapping into a
	 * .byte/.word operand, e.g. "$24,...,0w,#0").  Only digit-led runs are
	 * touched so real symbols ending in "w" (FooW) survive.
	 *
	 * @param code the data-directive text to clean
	 */
	private static String stripDataJunk(final String code)
	{
		String out = code.replaceAll("(?i)([0-9][0-9A-Fa-f]*)w(?=,|$)", "$1");
		out = out.replaceAll("(?i)(?<=,)\\s*w\\s*(?=,)", "");
		// a trailing ",#value" is decayed page-damage noise, not a data byte
		// (ca65 rejects '#' in data operands, and the source never wrote one).
		out = out.replaceAll("(?i),\\s*#[0-9A-Fa-f$]+\\s*$", "");
		// .res/.block count operands fused with the next line's fragment
		out = salvageResOperand(out);
		return out;
	}

	/** 
	 * "$NN" -> "@NN" for numeric-local references ("$NN" as a first-operand address on a
	 * non-data line, matching geoAssembler's local reference syntax).  Values after '#',
	 * '(', or a comma stay hex, as do operands of data directives.
	 *
	 * @param out the rewritten line
	 * @param st the current conversion state (for known numeric locals)
	 */
	private static String rewriteLocalRefs(String out, final State st)
	{
		// geoAssembler local labels are "nnnn$" (digits then '$'), for both the
		// definition and the branch reference (GEOProgrammer manual, Local Labels).
		// A "$nn" token is therefore a plain hex value, NOT a local reference:
		// geoAssembler treats "$nn" as a 0-based address within the current module
		// (its psect counter starts at 0).  Branch operands of that form are
		// rewritten to <module-origin> + $nn in emitModule(); everything else keeps
		// the hex value.  (The trailing "nn$" references were already rewritten to
		// ca65 "@nn" locals by NUMERIC_LOCAL_PATTERN.)
		return out;
	}

	private static String protectStrings(final String code, final List<String> strings)
	{
		final StringBuffer sb = new StringBuffer();
		final Matcher sm = STRING_PATTERN.matcher(code);
		int idx = strings.size();
		while(sm.find())
		{
			strings.add(sm.group());
			sm.appendReplacement(sb, Matcher.quoteReplacement("\u0001" + (idx++) + "\u0001"));
		}
		sm.appendTail(sb);
		return sb.toString();
	}

	private static String restoreStrings(final String code, final List<String> strings)
	{
		final StringBuffer sb = new StringBuffer();
		final Matcher rm = PLACEHOLDER_PATTERN.matcher(code);
		while(rm.find())
		{
			final int idx = Integer.parseInt(rm.group(1));
			rm.appendReplacement(sb, Matcher.quoteReplacement(strings.get(idx)));
		}
		rm.appendTail(sb);
		return sb.toString();
	}

	private static String makeLine(final String before, final String dname,
		final String operand, final String comment)
	{
		final StringBuilder sb = new StringBuilder();
		if(before.length() > 0)
			sb.append(before.trim());
		if(sb.length() > 0)
			sb.append('\t');
		sb.append(dname);
		if(operand.length() > 0)
			sb.append('\t').append(operand.trim());
		if(comment.length() > 0)
			sb.append('\t').append(comment);
		return sb.toString();
	}
}
