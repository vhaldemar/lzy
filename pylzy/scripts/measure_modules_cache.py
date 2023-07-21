#!/usr/bin/env python

from __future__ import annotations

import sys
import lzy.env.explorer.search as _s

### just for measuring
import torch
import abc
import typing_extensions
###


def measuring_decorator(func):
    cache = {}

    def wrapper(module):
        cache_stats = func.cache_info()

        result = func(module)

        if cache_stats.currsize < func.cache_info().currsize:
            cache[module] = result

        return result

    wrapper._cache = cache

    return wrapper


_s.get_direct_module_dependencies = measuring_decorator(_s.get_direct_module_dependencies)
_s.get_transitive_module_dependencies = measuring_decorator(_s.get_transitive_module_dependencies)


def print_cache_stats(cache):
    print(f'len: {len(cache)}')

    size = sys.getsizeof(cache)
    for value in cache.values():
        size += sys.getsizeof(value)

    print(f'mem size (kb): {size // 1024}')


if __name__ == '__main__':

    _s.get_transitive_namespace_dependencies(globals())

    print('get_direct_module_dependencies cache:')
    print_cache_stats(_s.get_direct_module_dependencies._cache)

    print()
    print('get_transitive_module_dependencies cache:')
    print_cache_stats(_s.get_transitive_module_dependencies._cache)
