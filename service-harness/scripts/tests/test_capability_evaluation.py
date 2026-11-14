"""Protect diagnostic evidence semantics, independent of any model implementation."""
import io
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from capability_evaluation import collect_sse, redact, check_decision, visual_prediction, PHOTO_PROFILES


class DiagnosticTest(unittest.TestCase):
    def test_people_detection_routes_to_thing_and_uses_structured_boxes(self):
        unit, _, option = PHOTO_PROFILES['people']
        self.assertEqual((unit,option['profile'],option['classes']),('SpecializedDetection','thing',['person']))
        self.assertEqual(visual_prediction('people',dict(answer='',resultPayload=dict(profile='thing',refs=[dict(content='person',boxes=[{},{}])]))),(True,2))
        self.assertEqual(visual_prediction('people',dict(answer='两人',resultPayload=dict(profile='thing',refs=[]))),(False,0))
        self.assertEqual(visual_prediction('people',dict(resultPayload=dict(profile='thing'))),(None,None))

    def test_fragmented_tool_and_usage(self):
        events = [
            dict(id="r", choices=[dict(index=0,delta=dict(tool_calls=[dict(index=0,id="call_",function=dict(name="calc",arguments='{"x":'))]))]),
            dict(id="r", choices=[dict(index=0,delta=dict(tool_calls=[dict(index=0,id="1",function=dict(arguments="1}"))]),finish_reason="tool_calls")]),
            dict(choices=[], usage=dict(prompt_tokens=12,completion_tokens=4)),
        ]
        raw = "".join("data: "+json.dumps(e)+"\n\n" for e in events)+"data: [DONE]\n\n"
        result = collect_sse(io.BytesIO(raw.encode()))
        self.assertEqual(result["choices"][0]["message"]["tool_calls"][0],dict(id="call_1",type="function",function=dict(name="calc",arguments='{"x":1}')))
        self.assertEqual(result["usage"]["prompt_tokens"],12)

    def test_truncated_stream_is_not_completion(self):
        with self.assertRaisesRegex(ValueError,"INCOMPLETE_SSE"):
            collect_sse(io.BytesIO(b'data: {"choices":[{"delta":{"content":"partial"}}]}\n\n'))

    def test_redacts_url_secret_and_nested_credentials(self):
        secret="test-sensitive-value"
        v=redact(dict(path="/base/"+secret, items=[dict(token=secret)], authorization="Bearer xxx"),secret)
        self.assertNotIn(secret,json.dumps(v))
        self.assertNotIn("Bearer xxx",json.dumps(v))

    def test_calculator_whitespace_is_semantically_equal(self):
        expected=dict(kind="tool_calls",tool="common.calculator.evaluate",arguments=dict(expression="17+25"))
        actual=dict(kind="tool_calls",calls=[dict(tool=expected["tool"],argumentsJson='{"expression":"17 + 25"}')])
        self.assertTrue(check_decision(actual,expected)[0])
        actual["calls"][0]["argumentsJson"]='{"expression":"17+26"}'
        self.assertFalse(check_decision(actual,expected)[0])

    def test_absent_payload_is_unscorable_empty_boxes_negative(self):
        self.assertIsNone(visual_prediction("fire",{})[0])
        self.assertEqual(visual_prediction("fire",dict(resultPayload=dict(refs=[]))),(False,None))
        self.assertEqual(visual_prediction("fire",dict(resultPayload=dict(refs=[dict(content="smoke",boxes=[{}])]))),(True,None))

    def test_count_comes_from_answer_not_boxes(self):
        self.assertEqual(visual_prediction("people",dict(answer="我在图像中可以数到 2 个。",resultPayload=dict(refs=[]))),(True,2))
        self.assertEqual(visual_prediction("people",dict(answer="没有人",resultPayload=dict(refs=[dict(boxes=[{}])]))),(False,0))
        self.assertEqual(visual_prediction("people",dict(answer="无法确定")),(None,None))

    def test_explicit_count_phrases_and_successful_no_object(self):
        for answer in ("<objects><obj0><obj3><obj1></objects> 我找到的总数是3。", "我在给定的图像中可以数到的是3。", "数量是 3。"):
            self.assertEqual(visual_prediction("people",dict(answer=answer)),(True,3))
        self.assertEqual(visual_prediction("people",dict(answer="没有请求的对象存在。")),(False,0))
        self.assertEqual(visual_prediction("people",dict(answer="<objects><obj0><obj3><obj1></objects>")),(None,None))

    def test_obstacle_boolean_is_strict(self):
        self.assertEqual(visual_prediction("obstacle",dict(answer='```json\n[{"blocked":false,"reason":"clear"}]\n```')),(False,None))
        self.assertEqual(visual_prediction("obstacle",dict(answer='{"blocked":"false"}')),(None,None))
        self.assertEqual(visual_prediction("obstacle",dict(answer='null')),(None,None))


if __name__ == "__main__":
    unittest.main()
