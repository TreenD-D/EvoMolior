package com.achulkov.evomolior.core.atlas

import java.util.Base64

/** Renders the closed DSL AST into a fixed bounded Python interpreter executable. */
object ToolDslPythonRuntime {
    /**
     * Embeds canonical [programJson] as inert data in a deterministic runtime wrapper.
     *
     * @param programJson Serialized [ToolDslProgram].
     * @return Executable Python source containing no model-authored source code.
     */
    fun render(programJson: String): String {
        val encoded = Base64.getEncoder().encodeToString(programJson.toByteArray(Charsets.UTF_8))
        return """
            #!/usr/bin/env python3
            import base64
            import json
            import math
            import random
            import sys

            PROGRAM = json.loads(base64.b64decode("$encoded").decode("utf-8"))
            MAX_STEPS = 1000
            MAX_LITERAL_DEPTH = 32
            steps = 0

            def fail(message):
                raise ValueError(message)

            def number(value):
                if isinstance(value, bool) or not isinstance(value, (int, float)):
                    fail("numeric operand required")
                if not math.isfinite(float(value)):
                    fail("non-finite numeric value")
                return value

            def decode_literal(node, depth=0):
                if depth > MAX_LITERAL_DEPTH or not isinstance(node, dict):
                    fail("invalid literal value")
                literal_type = node.get("type")
                if literal_type == "null":
                    return None
                if literal_type == "string":
                    value = node.get("value")
                    if not isinstance(value, str):
                        fail("invalid string literal")
                    return value
                if literal_type == "boolean":
                    value = node.get("value")
                    if not isinstance(value, bool):
                        fail("invalid boolean literal")
                    return value
                if literal_type == "integer":
                    value = node.get("value")
                    if isinstance(value, bool) or not isinstance(value, int):
                        fail("invalid integer literal")
                    return value
                if literal_type == "number":
                    return number(node.get("value"))
                if literal_type == "array":
                    values = node.get("values")
                    if not isinstance(values, list):
                        fail("invalid array literal")
                    return [decode_literal(value, depth + 1) for value in values]
                if literal_type == "object":
                    entries = node.get("entries")
                    if not isinstance(entries, list):
                        fail("invalid object literal")
                    result = {}
                    for entry in entries:
                        if not isinstance(entry, dict) or not isinstance(entry.get("key"), str):
                            fail("invalid object literal entry")
                        key = entry["key"]
                        if key in result:
                            fail("duplicate object literal key")
                        result[key] = decode_literal(entry.get("value"), depth + 1)
                    return result
                fail("unsupported literal value")

            def evaluate(node, inputs, rng):
                global steps
                steps += 1
                if steps > MAX_STEPS:
                    fail("DSL evaluation step limit exceeded")
                node_type = node["type"]
                if node_type == "input":
                    field = node["field"]
                    if field not in inputs:
                        fail("missing input field")
                    return inputs[field]
                if node_type == "literal":
                    return decode_literal(node["value"])
                if node_type == "unary":
                    value = evaluate(node["operand"], inputs, rng)
                    operation = node["operation"]
                    if operation == "NEGATE":
                        return -number(value)
                    if operation == "NOT":
                        if not isinstance(value, bool):
                            fail("boolean operand required")
                        return not value
                    if operation == "ABSOLUTE":
                        return abs(number(value))
                    if operation == "LENGTH":
                        if not isinstance(value, (str, list, dict)):
                            fail("sized operand required")
                        return len(value)
                    fail("unsupported unary operation")
                if node_type == "binary":
                    left = evaluate(node["left"], inputs, rng)
                    operation = node["operation"]
                    if operation == "AND":
                        if not isinstance(left, bool):
                            fail("boolean operand required")
                        if not left:
                            return False
                        right = evaluate(node["right"], inputs, rng)
                        if not isinstance(right, bool):
                            fail("boolean operand required")
                        return right
                    if operation == "OR":
                        if not isinstance(left, bool):
                            fail("boolean operand required")
                        if left:
                            return True
                        right = evaluate(node["right"], inputs, rng)
                        if not isinstance(right, bool):
                            fail("boolean operand required")
                        return right
                    right = evaluate(node["right"], inputs, rng)
                    if operation == "ADD":
                        return number(left) + number(right)
                    if operation == "SUBTRACT":
                        return number(left) - number(right)
                    if operation == "MULTIPLY":
                        return number(left) * number(right)
                    if operation == "DIVIDE":
                        divisor = number(right)
                        if divisor == 0:
                            fail("division by zero")
                        return number(left) / divisor
                    if operation == "MINIMUM":
                        return min(number(left), number(right))
                    if operation == "MAXIMUM":
                        return max(number(left), number(right))
                    if operation == "LESS_THAN":
                        return number(left) < number(right)
                    if operation == "LESS_THAN_OR_EQUAL":
                        return number(left) <= number(right)
                    if operation == "GREATER_THAN":
                        return number(left) > number(right)
                    if operation == "GREATER_THAN_OR_EQUAL":
                        return number(left) >= number(right)
                    if operation == "EQUAL":
                        return left == right
                    if operation == "NOT_EQUAL":
                        return left != right
                    fail("unsupported binary operation")
                if node_type == "conditional":
                    condition = evaluate(node["condition"], inputs, rng)
                    if not isinstance(condition, bool):
                        fail("boolean condition required")
                    branch = node["whenTrue"] if condition else node["whenFalse"]
                    return evaluate(branch, inputs, rng)
                if node_type == "aggregate":
                    values = evaluate(node["values"], inputs, rng)
                    if not isinstance(values, list):
                        fail("aggregate requires array")
                    operation = node["operation"]
                    if operation == "COUNT":
                        return len(values)
                    numeric = [number(value) for value in values]
                    if not numeric:
                        fail("numeric aggregate requires non-empty array")
                    if operation == "SUM":
                        return sum(numeric)
                    if operation == "MEAN":
                        return sum(numeric) / len(numeric)
                    if operation == "MINIMUM":
                        return min(numeric)
                    if operation == "MAXIMUM":
                        return max(numeric)
                    fail("unsupported aggregate operation")
                if node_type == "random_uniform":
                    if rng is None:
                        fail("random operation requires explicit seed")
                    minimum = number(evaluate(node["minimum"], inputs, rng))
                    maximum = number(evaluate(node["maximum"], inputs, rng))
                    if maximum < minimum:
                        fail("random domain is invalid")
                    return rng.uniform(minimum, maximum)
                fail("unsupported DSL expression")

            inputs = json.load(sys.stdin)
            if not isinstance(inputs, dict):
                fail("input must be a JSON object")
            seed_field = PROGRAM.get("randomSeedInputField")
            rng = None
            if seed_field is not None:
                seed = inputs.get(seed_field)
                if isinstance(seed, bool) or not isinstance(seed, int):
                    fail("explicit integer seed required")
                rng = random.Random(seed)
            output = {
                binding["field"]: evaluate(binding["expression"], inputs, rng)
                for binding in PROGRAM["outputs"]
            }
            sys.stdout.write(json.dumps(output, separators=(",", ":"), sort_keys=True))
        """.trimIndent()
    }
}
