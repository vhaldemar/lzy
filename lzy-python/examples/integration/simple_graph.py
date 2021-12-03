from lzy.api import op, LzyEnv


def main():
    @op
    def str_gen() -> str:
        return "More meaningful str than ever before"

    @op
    def foo(a: int) -> int:
        return a + 1

    @op
    def bar(a: int, v: str) -> str:
        # noinspection PyTypeChecker
        return v + "" + str(a)

    with LzyEnv(user="artolord", server_url="20.54.88.226:8899", private_key_path="/Users/artolord/.ssh/private.pem"):
        s = str_gen()
        f = foo(2)
        b = bar(f, s)

    print(b)


if __name__ == '__main__':
    main()
