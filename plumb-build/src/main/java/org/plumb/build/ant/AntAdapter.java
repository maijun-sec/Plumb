package org.plumb.build.ant;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.plumb.build.BuildAdapter;
import org.plumb.build.BuildAdapterException;
import org.plumb.build.BuildProject;
import org.plumb.build.SourceFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * R-833 P1 ? Apache Ant build-system adapter.
 *
 * <p>Recognises {@code build.xml} at the project root. Uses the standard JDK {@code
 * javax.xml.parsers} API (no extra dependency) to walk the Ant script.
 *
 * <h2>What we parse</h2>
 *
 * <ul>
 *   <li><b>source directories</b> — resolved by walking the Ant project for a target with {@code
 *       <javac srcdir="..."/>} (including multiple srcdirs separated by {@code :} or {@code ;} on
 *       the OS path-separator / Ant's antlib-style), the {@code <src><path>...</path></src>} nested
 *       form, or {@code <fileset dir="..."/>} and {@code <dirset dir="..."/>}. We support both
 *       literal {@code srcdir} attributes and {@code srcdir="${src.dir}"} property references: when
 *       a property reference is encountered we resolve it against the {@code <property>} entries in
 *       the same file (in document order).
 *   <li><b>classpath</b> — from a target's nested {@code <classpath><pathelement
 *       path="..."/>...</classpath>}. Multiple classpaths are concatenated; {@code pathelement
 *       path="..."} is split on the OS path separator.
 *   <li><b>import</b> — {@code <import file="sub-build.xml"/>} is followed so a multi-file Ant
 *       project contributes its sources and classpath. Cycle detection guards against
 *       self-referential imports.
 * </ul>
 *
 * <h2>What we do NOT parse (out of scope)</h2>
 *
 * <ul>
 *   <li>Ant macros ({@code <macrodef>}) ? we only handle literal targets.
 *   <li>Custom Ant tasks ? only the standard javac / path / classpath.
 * </ul>
 */
public final class AntAdapter implements BuildAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(AntAdapter.class);
  private static final String SYSTEM_NAME = "ant";

  @Override
  public String systemName() {
    return SYSTEM_NAME;
  }

  @Override
  public boolean probe(Path projectRoot) {
    if (projectRoot == null) return false;
    return Files.isRegularFile(projectRoot.resolve("build.xml"));
  }

  @Override
  public BuildProject discover(Path projectRoot) {
    if (!probe(projectRoot)) {
      throw new BuildAdapterException("Ant project root must contain build.xml: " + projectRoot);
    }
    // Walk the build.xml dependency graph (via <import>); aggregate sources and classpath across
    // every file we encounter.
    List<Path> sourceFiles = new ArrayList<>();
    Set<Path> classpath = new LinkedHashSet<>();
    Deque<Path> queue = new ArrayDeque<>();
    Set<Path> visited = new LinkedHashSet<>();
    queue.add(projectRoot.resolve("build.xml"));
    while (!queue.isEmpty()) {
      Path buildFile = queue.poll();
      if (!visited.add(buildFile.toAbsolutePath().normalize())) {
        continue; // cycle / duplicate — skip
      }
      Path importRoot = buildFile.getParent();
      Document doc = parseXml(buildFile);
      Map<String, String> properties = loadProperties(doc);
      // Inherit parent's properties so imports see their caller's variables.
      // For simplicity we just process each file's own properties; we don't propagate across
      // imports because the rare user can supply them via --config or CLI.

      // Follow <import file="..."/> recursively
      NodeList imports = doc.getElementsByTagName("import");
      for (int i = 0; i < imports.getLength(); i++) {
        Element imp = (Element) imports.item(i);
        String file = imp.getAttribute("file");
        if (file == null || file.isBlank()) continue;
        Path child = Path.of(file);
        if (!child.isAbsolute()) child = importRoot.resolve(file);
        if (Files.isRegularFile(child)) queue.add(child);
      }

      // Iterate every <target> looking for a javac task.
      NodeList targets = doc.getElementsByTagName("target");
      for (int i = 0; i < targets.getLength(); i++) {
        Element tgt = (Element) targets.item(i);
        NodeList children = tgt.getChildNodes();
        for (int j = 0; j < children.getLength(); j++) {
          if (!(children.item(j) instanceof Element)) continue;
          Element child = (Element) children.item(j);
          String tag = child.getTagName();
          if ("javac".equals(tag)) {
            // 1. <javac srcdir="..."/> (one or many — split on : / ; / OS path sep)
            String srcdir = child.getAttribute("srcdir");
            if (srcdir != null && !srcdir.isBlank()) {
              String resolved = resolveProperty(srcdir, properties);
              for (String one : splitSrcdir(resolved)) {
                Path root = projectRoot.resolve(one);
                sourceFiles.addAll(SourceFiles.walk(root, sourceExtensions()));
              }
            }
            // 2. <javac><src><path>...</path></src></javac> — nested <path> form
            NodeList srcPaths = child.getElementsByTagName("path");
            for (int k = 0; k < srcPaths.getLength(); k++) {
              Element pe = (Element) srcPaths.item(k);
              String p = pe.getAttribute("path");
              if (p == null || p.isBlank()) p = pe.getAttribute("location");
              if (p == null || p.isBlank()) continue;
              for (String one : splitSrcdir(resolveProperty(p, properties))) {
                Path root = projectRoot.resolve(one);
                sourceFiles.addAll(SourceFiles.walk(root, sourceExtensions()));
              }
            }
            // 3. <classpath><pathelement path="..."/></classpath>
            NodeList cps = child.getElementsByTagName("classpath");
            for (int k = 0; k < cps.getLength(); k++) {
              Element cp = (Element) cps.item(k);
              NodeList paths = cp.getElementsByTagName("pathelement");
              for (int l = 0; l < paths.getLength(); l++) {
                Element pe = (Element) paths.item(l);
                String p = pe.getAttribute("path");
                if (p == null || p.isBlank()) p = pe.getAttribute("location");
                if (p == null || p.isBlank()) continue;
                String resolved = resolveProperty(p, properties);
                for (String seg :
                    resolved.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                  if (seg.isBlank()) continue;
                  Path entry = Path.of(seg);
                  if (!entry.isAbsolute()) entry = projectRoot.resolve(seg);
                  classpath.add(entry);
                }
              }
            }
          } else if ("fileset".equals(tag) || "dirset".equals(tag)) {
            // <fileset dir="src"/> or <dirset dir="src/include"/> (top-level)
            String dir = child.getAttribute("dir");
            if (dir != null && !dir.isBlank()) {
              Path root = projectRoot.resolve(resolveProperty(dir, properties));
              sourceFiles.addAll(SourceFiles.walk(root, sourceExtensions()));
            }
          }
        }
        // Nested <fileset>/<dirset> inside other tasks (e.g. <copy><fileset dir="src"/></copy>).
        // The DOM API's getElementsByTagName recurses into the whole document — re-do it scoped
        // to each target so we don't double-count.
        NodeList filesets = tgt.getElementsByTagName("fileset");
        for (int fs = 0; fs < filesets.getLength(); fs++) {
          Element fileset = (Element) filesets.item(fs);
          String dir = fileset.getAttribute("dir");
          if (dir != null && !dir.isBlank()) {
            Path root = projectRoot.resolve(resolveProperty(dir, properties));
            sourceFiles.addAll(SourceFiles.walk(root, sourceExtensions()));
          }
        }
        NodeList dirsets = tgt.getElementsByTagName("dirset");
        for (int ds = 0; ds < dirsets.getLength(); ds++) {
          Element dirset = (Element) dirsets.item(ds);
          String dir = dirset.getAttribute("dir");
          if (dir != null && !dir.isBlank()) {
            Path root = projectRoot.resolve(resolveProperty(dir, properties));
            sourceFiles.addAll(SourceFiles.walk(root, sourceExtensions()));
          }
        }
      }
    }

    LOG.info(
        "Ant: project root {} ({} source files, {} classpath entries)",
        projectRoot,
        sourceFiles.size(),
        classpath.size());

    return BuildProject.builder(SYSTEM_NAME)
        .version("Ant adapter v1")
        .sourceFiles(sourceFiles.stream().distinct().toList())
        .classpathJars(new ArrayList<>(classpath))
        .sourceRoots(List.of(projectRoot))
        .moduleRoots(List.of(projectRoot))
        .build();
  }

  /**
   * Split an Ant srcdir into multiple directories. Ant supports {@code srcdir="a:b:c"} (Unix
   * path-separator) and {@code srcdir="a;b;c"} (Ant's explicit multi-srcdir separator); some
   * scripts also use {@code srcdir="a,c"} or {@code srcdir="a c"} (whitespace) by convention. We
   * honour the OS path-separator, {@code ;}, {@code :}, {@code ,}, and whitespace.
   */
  private static List<String> splitSrcdir(String value) {
    if (value == null || value.isBlank()) return List.of();
    String sep = java.io.File.pathSeparator;
    // Ant historically uses ';' as the multi-srcdir separator on all platforms; many scripts
    // also accept ':', ',' and whitespace.
    String[] parts =
        value.split(
            Regex.quote(sep)
                + "|"
                + Regex.quote(";")
                + "|"
                + Regex.quote(":")
                + "|"
                + Regex.quote(",")
                + "|\\s+");
    List<String> out = new ArrayList<>();
    for (String p : parts) {
      String trimmed = p.trim();
      if (!trimmed.isEmpty()) out.add(trimmed);
    }
    return out;
  }

  /**
   * Resolve a single Ant value, substituting {@code ${prop}} placeholders from the properties map.
   * Multiple placeholders are not supported ? the user is expected to keep values simple.
   */
  private static String resolveProperty(String value, Map<String, String> props) {
    if (value == null || !value.contains("${")) return value;
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < value.length()) {
      int start = value.indexOf("${", i);
      if (start < 0) {
        out.append(value, i, value.length());
        break;
      }
      int end = value.indexOf('}', start + 2);
      if (end < 0) {
        out.append(value, i, value.length());
        break;
      }
      out.append(value, i, start);
      String key = value.substring(start + 2, end);
      out.append(props.getOrDefault(key, ""));
      i = end + 1;
    }
    return out.toString();
  }

  /**
   * Load the {@code <property name="..." value="..."/>} entries (and the {@code <property
   * file="..."/>} variant) into a flat map. We don't chase external files for simplicity; if the
   * build uses {@code <property file="..."/>} the user can pass the result via the CLI's --config
   * option.
   */
  private static Map<String, String> loadProperties(Document doc) {
    Map<String, String> map = new LinkedHashMap<>();
    NodeList props = doc.getElementsByTagName("property");
    for (int i = 0; i < props.getLength(); i++) {
      Element p = (Element) props.item(i);
      String name = p.getAttribute("name");
      String value = p.getAttribute("value");
      if (name != null && !name.isBlank() && value != null && !value.isBlank()) {
        map.put(name, value);
      }
      // <property location="..."/> binds to a project-rooted path.
      String location = p.getAttribute("location");
      if (name != null && !name.isBlank() && location != null && !location.isBlank()) {
        map.put(name, location);
      }
    }
    return map;
  }

  private static Document parseXml(Path path) {
    try (InputStream in = Files.newInputStream(path)) {
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      // Hardening: disable external entities to avoid XXE on hostile
      // build.xml files.
      dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
      dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      DocumentBuilder db = dbf.newDocumentBuilder();
      return db.parse(in);
    } catch (IOException | ParserConfigurationException | SAXException e) {
      throw new BuildAdapterException(
          "Failed to parse Ant build file " + path + ": " + e.getMessage(), e);
    }
  }

  /** Tiny indirection so the regex quote call doesn't need a static import. */
  private static final class Regex {
    static String quote(String s) {
      return java.util.regex.Pattern.quote(s);
    }
  }
}
