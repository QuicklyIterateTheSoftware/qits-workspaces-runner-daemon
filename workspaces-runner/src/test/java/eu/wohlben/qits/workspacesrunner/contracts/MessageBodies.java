package eu.wohlben.qits.workspacesrunner.contracts;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactDslJsonArray;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslJsonRootValue;
import au.com.dius.pact.core.model.matchingrules.NullMatcher;
import au.com.dius.pact.core.model.matchingrules.RegexMatcher;
import au.com.dius.pact.core.model.matchingrules.TypeMatcher;
import eu.wohlben.qits.workspacesrunner.contracts.MessageGoldenMasters.Recorded;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>A recorded frame as a pact message body</b> (qits-950): the frame verbatim as the example,
 * with matchers read off the index's {@code frozen} lists, exactly as qits-workspaces-service's
 * {@code GoldenMasters.messageInteraction} builds them for the opposite direction — so both message
 * pacts of the socket hold one dialect:
 *
 * <ul>
 *   <li>{@code $.type} — exact: it is the frame itself, and a type match would let any frame stand
 *       for any other;
 *   <li>{@code frozen.ids} — a UUID regex; {@code frozen.instants} — an ISO-8601 regex, any
 *       fraction length and offset;
 *   <li>every other leaf ({@code frozen.strings}, {@code frozen.numbers} among them) — a type
 *       match, widened to {@code OR null} where an array element held null;
 *   <li>a non-empty array — {@code minMaxArrayLike(n, n)} against one merged template; but an array
 *       of objects that do not share one key set (a {@code take}'s mounts do; a health report's
 *       checks would not) element by element at its recorded length, because one merged template
 *       would demand every element's keys of every element;
 *   <li>an empty array — compared as exactly that, having no element to make a template of.
 * </ul>
 *
 * <p><b>The whole frame is the contract</b>, nothing cut to what a consumer reads: the codec
 * decodes every field.
 */
public final class MessageBodies {

  /** An ISO-8601 timestamp, any fraction length, Z or a numeric offset (the service's regex). */
  public static final String ISO_INSTANT =
      "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:?\\d{2})$";

  static final String UUID_REGEX =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

  private MessageBodies() {}

  /** {@code frame}, recorded as {@code recorded}, with its matchers. */
  public static DslPart body(Recorded recorded, JsonObject frame) {
    if (!recorded.frame().equals(frame.getString("type"))) {
      throw new IllegalStateException(
          "golden master "
              + recorded.state()
              + "/"
              + recorded.operationId()
              + " is no '"
              + recorded.frame()
              + "' frame: "
              + frame.encode());
    }
    PactDslJsonBody root = new PactDslJsonBody();
    fillObject(root, Shape.of(frame), "$", recorded);
    return root;
  }

  private static void fillObject(PactDslJsonBody target, Shape shape, String path, Recorded op) {
    for (Map.Entry<String, Shape> field : shape.fields.entrySet()) {
      String name = field.getKey();
      Shape child = field.getValue();
      String childPath = path + "." + name;
      switch (child.kind) {
        case NULL -> target.nullValue(name);
        case LEAF -> {
          if ("$.type".equals(childPath)) {
            target.stringValue(name, (String) child.example);
          } else {
            leaf(target, name, child, childPath, op);
          }
        }
        case OBJECT -> {
          if (child.nullable) {
            throw unsupported(op, childPath, "an object that is null in some elements");
          }
          PactDslJsonBody nested = target.object(name);
          fillObject(nested, child, childPath, op);
          nested.closeObject();
        }
        case ARRAY -> {
          if (child.mixedObjects()) {
            positional(target, name, child, childPath, op);
          } else {
            array(target, name, child, childPath, op);
          }
        }
      }
    }
  }

  private static void positional(
      PactDslJsonBody target, String name, Shape array, String path, Recorded op) {
    PactDslJsonArray elements = target.array(name);
    for (Shape element : array.elements) {
      PactDslJsonBody nested = elements.object();
      fillObject(nested, element, path + "[*]", op);
      nested.closeObject();
    }
    elements.closeArray();
  }

  private static void leaf(
      PactDslJsonBody target, String name, Shape leaf, String path, Recorded op) {
    Object example = leaf.example;
    if (op.ids().contains(path)) {
      String text = requireText(example, path, op, "ids");
      if (leaf.nullable) {
        target.or(name, text, new RegexMatcher(UUID_REGEX, text), NullMatcher.INSTANCE);
      } else {
        target.uuid(name, text);
      }
    } else if (op.instants().contains(path)) {
      String text = requireText(example, path, op, "instants");
      if (leaf.nullable) {
        target.or(name, text, new RegexMatcher(ISO_INSTANT, text), NullMatcher.INSTANCE);
      } else {
        target.stringMatcher(name, ISO_INSTANT, text);
      }
    } else if (leaf.nullable) {
      target.or(name, example, TypeMatcher.INSTANCE, NullMatcher.INSTANCE);
    } else if (example instanceof String text) {
      target.stringType(name, text);
    } else if (example instanceof Number number) {
      target.numberType(name, number);
    } else if (example instanceof Boolean bool) {
      target.booleanType(name, bool);
    } else {
      throw unsupported(op, path, "a " + example.getClass().getSimpleName() + " leaf");
    }
  }

  private static void array(
      PactDslJsonBody target, String name, Shape array, String path, Recorded op) {
    if (array.nullable) {
      throw unsupported(op, path, "an array that is null in some elements");
    }
    int n = array.length;
    if (n == 0) {
      target.array(name).closeArray();
      return;
    }
    Shape element = array.element;
    String elementPath = path + "[*]";
    switch (element.kind) {
      case OBJECT -> {
        PactDslJsonBody template = target.minMaxArrayLike(name, n, n, n);
        fillObject(template, element, elementPath, op);
        DslPart closed = template.closeObject();
        ((PactDslJsonArray) closed).closeArray();
      }
      case LEAF -> target.minMaxArrayLike(name, n, n, rootLeaf(element, elementPath, op), n);
      default -> throw unsupported(op, elementPath, "an array of " + element.kind);
    }
  }

  private static PactDslJsonRootValue rootLeaf(Shape leaf, String path, Recorded op) {
    if (leaf.nullable) {
      throw unsupported(op, path, "an array holding nulls");
    }
    Object example = leaf.example;
    if (op.ids().contains(path)) {
      return PactDslJsonRootValue.uuid(requireText(example, path, op, "ids"));
    }
    if (op.instants().contains(path)) {
      return PactDslJsonRootValue.stringMatcher(
          ISO_INSTANT, requireText(example, path, op, "instants"));
    }
    if (example instanceof String text) {
      return PactDslJsonRootValue.stringType(text);
    }
    if (example instanceof Number number) {
      return PactDslJsonRootValue.numberType(number);
    }
    if (example instanceof Boolean bool) {
      return PactDslJsonRootValue.booleanType(bool);
    }
    throw unsupported(op, path, "a " + example.getClass().getSimpleName() + " array element");
  }

  private static String requireText(Object example, String path, Recorded op, String list) {
    if (!(example instanceof String text)) {
      throw new IllegalStateException(
          "golden master "
              + op.state()
              + "/"
              + op.operationId()
              + ": frozen."
              + list
              + " names "
              + path
              + ", which holds "
              + example
              + ", not a string");
    }
    return text;
  }

  private static IllegalStateException unsupported(Recorded op, String path, String what) {
    return new IllegalStateException(
        "golden master "
            + op.state()
            + "/"
            + op.operationId()
            + ": "
            + path
            + " is "
            + what
            + ", which MessageBodies cannot express as a pact matcher yet");
  }

  /**
   * The structure of a recorded value, an array's elements MERGED into one template (field union, a
   * leaf's first non-null example, {@code nullable} wherever an element held null or lacked the
   * field) and also kept one by one, for an array of objects of differing shapes.
   */
  private static final class Shape {
    enum Kind {
      NULL,
      LEAF,
      OBJECT,
      ARRAY
    }

    Kind kind;
    boolean nullable;
    Object example;
    final LinkedHashMap<String, Shape> fields = new LinkedHashMap<>();
    Shape element;
    int length;
    final List<Shape> elements = new ArrayList<>();

    boolean mixedObjects() {
      if (kind != Kind.ARRAY
          || elements.size() < 2
          || !elements.stream().allMatch(e -> e.kind == Kind.OBJECT)) {
        return false;
      }
      String first = elements.get(0).structure();
      return elements.stream().anyMatch(e -> !e.structure().equals(first));
    }

    private String structure() {
      return switch (kind) {
        case NULL, LEAF -> "*";
        case ARRAY -> "[]";
        case OBJECT -> {
          StringBuilder out = new StringBuilder("{");
          new TreeMap<>(fields)
              .forEach(
                  (key, child) ->
                      out.append(key).append(':').append(child.structure()).append(','));
          yield out.append('}').toString();
        }
      };
    }

    static Shape of(Object node) {
      Shape shape = new Shape();
      if (node == null) {
        shape.kind = Kind.NULL;
        shape.nullable = true;
      } else if (node instanceof JsonObject object) {
        shape.kind = Kind.OBJECT;
        for (String key : object.fieldNames()) {
          shape.fields.put(key, of(object.getValue(key)));
        }
      } else if (node instanceof JsonArray array) {
        shape.kind = Kind.ARRAY;
        shape.length = array.size();
        for (int i = 0; i < array.size(); i++) {
          Object e = array.getValue(i);
          shape.element = shape.element == null ? of(e) : merge(shape.element, of(e));
          shape.elements.add(of(e));
        }
      } else {
        shape.kind = Kind.LEAF;
        shape.example = node;
      }
      return shape;
    }

    static Shape merge(Shape a, Shape b) {
      if (a.kind == Kind.NULL) {
        b.nullable = true;
        return b;
      }
      if (b.kind == Kind.NULL) {
        a.nullable = true;
        return a;
      }
      if (a.kind != b.kind) {
        throw new IllegalStateException(
            "golden master array elements disagree: " + a.kind + " and " + b.kind);
      }
      a.nullable |= b.nullable;
      switch (a.kind) {
        case OBJECT -> {
          List<String> keys = new ArrayList<>(a.fields.keySet());
          for (String key : b.fields.keySet()) {
            if (!keys.contains(key)) {
              keys.add(key);
            }
          }
          LinkedHashMap<String, Shape> merged = new LinkedHashMap<>();
          for (String key : keys) {
            Shape left = a.fields.get(key);
            Shape right = b.fields.get(key);
            merged.put(
                key,
                left == null
                    ? merge(of(null), right)
                    : right == null ? merge(left, of(null)) : merge(left, right));
          }
          a.fields.clear();
          a.fields.putAll(merged);
        }
        case ARRAY -> {
          a.length = Math.min(a.length, b.length);
          a.element =
              a.element == null
                  ? b.element
                  : b.element == null ? a.element : merge(a.element, b.element);
        }
        default -> {
          // LEAF: keep a's example; the matcher is a type match, so one example stands for all.
        }
      }
      return a;
    }
  }
}
