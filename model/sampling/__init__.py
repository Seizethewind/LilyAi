"""Next-token sampling, one strategy per file.

Greedy, temperature, top-k and top-p filters, and the final sampler.
"""
from model.sampling.greedy import greedy
from model.sampling.sample import sample_token
from model.sampling.temperature import apply_temperature
from model.sampling.top_k import top_k_filter
from model.sampling.top_p import top_p_filter

__all__ = ["greedy", "sample_token", "apply_temperature", "top_k_filter", "top_p_filter"]
