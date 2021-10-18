import functools
import logging
from typing import Callable

import sys

from ._proxy import proxy
from .buses import *
from .env import LzyEnv
from .lazy_op import LzyOp, LzyLocalOp, LzyRemoteOp
from .utils import print_lzy_ops, infer_return_type, is_lazy_proxy, lazy_proxy

logging.root.setLevel(logging.INFO)
handler = logging.StreamHandler(sys.stdout)
handler.setLevel(logging.DEBUG)
formatter = logging.Formatter(
    '%(asctime)s - %(name)s - %(levelname)s - %(message)s')
handler.setFormatter(formatter)
logging.root.addHandler(handler)


def op(func: Callable = None, *, output_type=None):
    if func is None:
        if output_type is None:
            raise ValueError(f'output_type should be not None')
        return op_(output_type=output_type)

    return_type = infer_return_type(func)
    if return_type is None:
        raise TypeError(f"{func} return type is not annotated."
                        f"Please for proper use of {op.__name__} annotate "
                        f"return type of your function.")
    return op_(output_type=return_type)(func)


def op_(*, input_types=None, output_type=None):
    def deco(f):
        @functools.wraps(f)
        def lazy(*args):
            # TODO: all possible arguments, including **kwargs and defaults
            nonlocal input_types

            # if input types are not specified then try to get types of
            # operation from args return types
            if input_types is None:
                # noinspection PyProtectedMember
                input_types = tuple(
                    arg._op.return_type if is_lazy_proxy(arg) else type(arg)
                    for arg in args
                )

            current_env = LzyEnv.get_active()
            if current_env is None:
                return f(*args)

            if current_env.is_local():
                lzy_op = LzyLocalOp(f, input_types, output_type, *args)
            else:
                lzy_op = LzyRemoteOp(current_env.servant(), f, input_types, output_type, *args)
            current_env.register_op(lzy_op)
            return lazy_proxy(lambda: lzy_op.materialize(), output_type, {'_op': lzy_op})

        return lazy

    return deco
