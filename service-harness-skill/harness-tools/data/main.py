from runner_sdk import context, invoke, result

result(invoke(context()['arguments']))
