from lzy.api.v1 import op, Lzy


@op(cache=True, version="1.1")
def foo_with_print(name: str, value: int) -> str:
    print("foo was called")
    return f"{name} is {value}"


@op(cache=False)
def bar_with_print(message: str) -> str:
    return f"message from bar: {message}"


@op
def buzz() -> int:
    print("buzz was called")
    return 42


if __name__ == '__main__':
    workflow_name = "wf"

    with Lzy().workflow(name=workflow_name, interactive=False):
        n = "number"
        v = 42
        for i in range(6):
            mes = bar_with_print(foo_with_print(n, v))
            print(mes)

    with Lzy().workflow(name=workflow_name, interactive=False):
        n = "number"
        v = 42
        mes_1 = bar_with_print(foo_with_print(n, v))
        mes_2 = f"buzz result: {buzz()}"
        print(mes_1)
        print(mes_2)
