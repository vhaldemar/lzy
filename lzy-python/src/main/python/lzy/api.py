import dataclasses
import inspect
from typing import Callable, Any, get_type_hints, Iterable, Union, _GenericAlias, Type, List, Tuple

NON_OVERLOADING_MEMBERS = ['__class__', '__getattribute__', '__setattr__', '__new__', '__init__',
                           '__init_subclass__', '__abstractmethods__', '__dict__', '__weakref__']


class LzyOp:
    def __init__(self, func: Callable, return_hint: Union[_GenericAlias, Type], *args):
        self._func = func
        self._args = args
        self._materialized = False
        self._materialization = None

        return_type = None
        if hasattr(return_hint, '__origin__'):
            return_type = return_hint.__origin__
        elif type(return_hint) == type:
            return_type = return_hint

        if return_type is not None:
            members = inspect.getmembers(return_type)
            for (k, v) in members:
                if k not in NON_OVERLOADING_MEMBERS:
                    # noinspection PyShadowingNames
                    setattr(self, k, lambda *a, k=k: getattr(self.materialize(), k)(*a))
                    # noinspection PyShadowingNames
                    setattr(LzyOp, k, lambda obj, *a, k=k: getattr(obj, k)(*a))

    def materialize(self) -> Any:
        if not self._materialized:
            self._materialization = self._func(*self._args)
            self._materialized = True
        return self._materialization

    def is_materialized(self) -> bool:
        return self._materialized

    def func(self) -> Callable:
        return self._func


def op(func: Callable) -> Callable:
    hints = get_type_hints(func)
    if 'return' not in hints:
        raise ValueError('Op should be annotated with return type')

    def lazy(*args) -> Any:
        env = None
        for stack in inspect.stack():
            lcls = stack.frame.f_locals
            for k, v in lcls.items():
                if type(v) == LzyEnv and v.active():
                    if env is None:
                        env = v
                    else:
                        raise ValueError('More than one started lzy environment found')

        if env is None:
            return func(*args)
        else:
            wrapper = LzyOp(func, hints['return'], *args)
            env.register(wrapper)
            return wrapper

    return lazy


class WhiteboardProxy:
    def __init__(self, whiteboard: Any):
        self._whiteboard = whiteboard
        self._already_set_fields = set()

    def disallow_multiple_writes(self) -> None:
        if self._whiteboard is not None:
            set_attr = getattr(self._whiteboard, '__setattr__')
            setattr(self._whiteboard, '__setattr__', lambda *a: self._fake_setattr(set_attr, *a))
            setattr(type(self._whiteboard), '__setattr__', lambda obj, *a: obj.__setattr__(*a))

    def disallow_writes(self) -> None:
        if self._whiteboard is not None:
            setattr(self._whiteboard, '__setattr__', lambda *a: self._raise_write_exception())
            setattr(type(self._whiteboard), '__setattr__', lambda obj, *a: obj.__setattr__(*a))

    def _fake_setattr(self, set_attr: Callable, name: str, value: Any) -> None:
        if name in self._already_set_fields:
            raise ValueError('Item has been already set to the whiteboard')
        self._already_set_fields.add(name)
        set_attr(name, value)

    def _raise_write_exception(self):
        raise ValueError('Writes to a whiteboard are forbidden after run')


class Bus:
    pass


class KeyedIteratorBus(Bus):
    def __init__(self, key_extractor: Callable[[Any], str]):
        super().__init__()
        self._key_extractor = key_extractor


class LzyEnv:
    # noinspection PyDefaultArgument
    def __init__(self, eager: bool = False, whiteboard: Any = None, buses: List[Tuple[Callable, Bus]] = []):
        self._wrappers = []
        self._entered = False
        self._exited = False
        self._eager = eager
        self._buses = list(buses)

        if whiteboard is not None and not dataclasses.is_dataclass(whiteboard):
            raise ValueError('Whiteboard should be a dataclass')
        self._whiteboard_proxy = WhiteboardProxy(whiteboard)

    def __enter__(self):
        self._entered = True
        self._whiteboard_proxy.disallow_multiple_writes()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.run()
        self._whiteboard_proxy.disallow_writes()
        self._exited = True

    def active(self) -> bool:
        return self._entered and not self._exited

    def register(self, wrapper: LzyOp) -> None:
        self._wrappers.append(wrapper)
        if self._eager:
            wrapper.materialize()

    def registered_ops(self) -> Iterable[LzyOp]:
        if not self._entered:
            raise ValueError('Fetching ops on a non-entered environment')
        return list(self._wrappers)

    def run(self) -> None:
        if not self._entered:
            raise ValueError('Run operation on a non-entered environment')
        if self._exited:
            raise ValueError('Run operation on an exited environment')
        # noinspection PyTypeChecker
        if len(self._wrappers) == 0:
            raise ValueError('No registered ops')
        for wrapper in self._wrappers:
            wrapper.materialize()


class LzyUtils:
    @staticmethod
    def print_lzy_ops(ops: Iterable[LzyOp]) -> None:
        for lzy_op in ops:
            inp = "("
            ret = ""
            hints = get_type_hints(lzy_op.func())
            keys = list(hints.keys())
            for i in range(len(keys)):
                if keys[i] != 'return':
                    inp += str(hints[keys[i]])
                    inp += ","
                else:
                    ret += str(hints[keys[i]])
            inp += ")"
            print(
                inp + " -> " + str(lzy_op.func()) + " -> " + ret + ", materialized=" + str(lzy_op.is_materialized()))
