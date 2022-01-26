from lzy.api import op, LzyRemoteEnv
from lzy.servant.terminal_server import TerminalConfig


@op
def raises() -> int:
    raise RuntimeError("Bad exception")


if __name__ == "__main__":
    config = TerminalConfig(user="phil")
    with LzyRemoteEnv(config=config):
        raises()
