from typing import Type, Callable, TypeVar


class TrickDescriptor:
    def __init__(self, attr, callback):
        self._f = attr
        self.callback = callback

    def __get__(self, instance, owner):
        f = self._f
        if instance is not None:
            # for attributes such that its __get__ requires (or just receives)
            # instance:
            # call given callback and put result as new instance
            return f.__get__(create_and_cache(type(instance), self.callback),
                             owner)
        # otherwise just call original __get__ without forcing evaluation
        return f.__get__(instance, owner)


def create_and_cache(proxy_cls, callback):
    if not hasattr(proxy_cls, '_origin'):
        proxy_cls._origin = callback()
    return proxy_cls._origin


class Proxifier(type):
    def __new__(cls, name, bases, attrs, t, origin_getter, cls_attrs):
        new_attrs = {
            k: TrickDescriptor(v, origin_getter) if hasattr(v, '__get__') else v
            for k, v in collect_attributes(t).items()
        }
        new_attrs.update(attrs)
        new_attrs.update(cls_attrs)

        # omg, this single line allows proxy to wrap types with __slots__
        # just leave it here
        new_attrs.pop('__slots__', None)

        return super().__new__(cls, name, bases, new_attrs)


def collect_attributes(cls):
    methods = {}
    for k in dir(cls):
        methods[k] = getattr(cls, k)
    return methods


T = TypeVar('T')


def proxy(origin_getter: Callable[[], T], t: Type,
          cls_attrs=None, obj_attrs=None):
    """
    Function which returns proxy on object, i.e. object which looks like original,
    but lazy and created by calling given callback at the last moment
    >>> a = proxy(lambda: 3, int)
    >>> for i in range(a):
    >>>     print(i)
    >>> 0
    >>> 1
    >>> 2
    """
    cls_attrs = cls_attrs or {}
    obj_attrs = obj_attrs or {}

    # yea, creates new class everytime
    # probably all this stuff could be done with just one `type` call
    #
    # __pearl, hope it's hidden__
    class pearl(metaclass=Proxifier, t=t, origin_getter=origin_getter,
                cls_attrs=cls_attrs):
        def __init__(self):
            for k, v in obj_attrs.items():
                setattr(self, k, v)

        def __getattr__(self, item):
            if item in obj_attrs:
                return super(type(self), self).__getattribute__(item)

            return getattr(create_and_cache(type(self), origin_getter), item)

        def __setattr__(self, item, value):
            # if trying to set attributes from obj_attrs to pearl obj
            # try to directly put stuff into __dict__ of pearl obj
            if item in obj_attrs:
                return super(type(self), self).__setattr__(item, value)

            # if trying to set smth unspecified then just redirect
            # setattr call to underlying original object
            return setattr(create_and_cache(type(self), origin_getter), item,
                           value)

    return pearl()
