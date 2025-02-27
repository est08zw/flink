################################################################################
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
################################################################################
import abc
import time
from functools import reduce
from itertools import chain
from typing import List, Tuple, Any, Dict, Union

from apache_beam.coders import PickleCoder

from pyflink.datastream.state import ValueStateDescriptor, ValueState, ListStateDescriptor, \
    ListState, MapStateDescriptor, MapState, ReducingStateDescriptor, ReducingState, \
    AggregatingStateDescriptor, AggregatingState
from pyflink.datastream import TimeDomain, TimerService
from pyflink.datastream.functions import RuntimeContext, ProcessFunction, KeyedProcessFunction, \
    KeyedCoProcessFunction
from pyflink.datastream.timerservice import TimerOperandType, InternalTimer, InternalTimerImpl
from pyflink.fn_execution import flink_fn_execution_pb2, operation_utils
from pyflink.fn_execution.state_data_view import extract_data_view_specs
from pyflink.fn_execution.beam.beam_coders import DataViewFilterCoder
from pyflink.fn_execution.operation_utils import extract_user_defined_aggregate_function
from pyflink.fn_execution.state_impl import RemoteKeyedStateBackend

from pyflink.fn_execution.window_assigner import TumblingWindowAssigner, CountTumblingWindowAssigner
from pyflink.fn_execution.window_trigger import EventTimeTrigger, ProcessingTimeTrigger, \
    CountTrigger

try:
    from pyflink.fn_execution.aggregate_fast import RowKeySelector, SimpleAggsHandleFunction, \
        GroupAggFunction, DistinctViewDescriptor, SimpleTableAggsHandleFunction, \
        GroupTableAggFunction
    from pyflink.fn_execution.window_aggregate_fast import SimpleNamespaceAggsHandleFunction, \
        GroupWindowAggFunction
    from pyflink.fn_execution.coder_impl_fast import InternalRow
    has_cython = True
except ImportError:
    from pyflink.fn_execution.aggregate_slow import RowKeySelector, SimpleAggsHandleFunction, \
        GroupAggFunction, DistinctViewDescriptor, SimpleTableAggsHandleFunction,\
        GroupTableAggFunction
    from pyflink.fn_execution.window_aggregate_slow import SimpleNamespaceAggsHandleFunction, \
        GroupWindowAggFunction
    has_cython = False

from pyflink.metrics.metricbase import GenericMetricGroup
from pyflink.table import FunctionContext, Row


# table operations
SCALAR_FUNCTION_URN = "flink:transform:scalar_function:v1"
TABLE_FUNCTION_URN = "flink:transform:table_function:v1"
STREAM_GROUP_AGGREGATE_URN = "flink:transform:stream_group_aggregate:v1"
STREAM_GROUP_TABLE_AGGREGATE_URN = "flink:transform:stream_group_table_aggregate:v1"
STREAM_GROUP_WINDOW_AGGREGATE_URN = "flink:transform:stream_group_window_aggregate:v1"
PANDAS_AGGREGATE_FUNCTION_URN = "flink:transform:aggregate_function:arrow:v1"
PANDAS_BATCH_OVER_WINDOW_AGGREGATE_FUNCTION_URN = \
    "flink:transform:batch_over_window_aggregate_function:arrow:v1"

# datastream operations
DATA_STREAM_STATELESS_FUNCTION_URN = "flink:transform:datastream_stateless_function:v1"
PROCESS_FUNCTION_URN = "flink:transform:process_function:v1"
KEYED_PROCESS_FUNCTION_URN = "flink:transform:keyed_process_function:v1"


class Operation(abc.ABC):
    def __init__(self, spec):
        super(Operation, self).__init__()
        self.spec = spec
        self.func, self.user_defined_funcs = self.generate_func(self.spec.serialized_fn)
        if self.spec.serialized_fn.metric_enabled:
            self.base_metric_group = GenericMetricGroup(None, None)
        else:
            self.base_metric_group = None

    def open(self):
        for user_defined_func in self.user_defined_funcs:
            if hasattr(user_defined_func, 'open'):
                user_defined_func.open(FunctionContext(self.base_metric_group))

    def close(self):
        for user_defined_func in self.user_defined_funcs:
            if hasattr(user_defined_func, 'close'):
                user_defined_func.close()

    def finish(self):
        self._update_gauge(self.base_metric_group)

    def _update_gauge(self, base_metric_group):
        if base_metric_group is not None:
            for name in base_metric_group._flink_gauge:
                flink_gauge = base_metric_group._flink_gauge[name]
                beam_gauge = base_metric_group._beam_gauge[name]
                beam_gauge.set(flink_gauge())
            for sub_group in base_metric_group._sub_groups:
                self._update_gauge(sub_group)

    @abc.abstractmethod
    def generate_func(self, serialized_fn) -> Tuple:
        pass


class ScalarFunctionOperation(Operation):
    def __init__(self, spec):
        super(ScalarFunctionOperation, self).__init__(spec)

    def generate_func(self, serialized_fn):
        """
        Generates a lambda function based on udfs.
        :param serialized_fn: serialized function which contains a list of the proto
                              representation of the Python :class:`ScalarFunction`
        :return: the generated lambda function
        """
        scalar_functions, variable_dict, user_defined_funcs = reduce(
            lambda x, y: (
                ','.join([x[0], y[0]]),
                dict(chain(x[1].items(), y[1].items())),
                x[2] + y[2]),
            [operation_utils.extract_user_defined_function(udf) for udf in serialized_fn.udfs])
        generate_func = eval('lambda value: [%s]' % scalar_functions, variable_dict)
        return generate_func, user_defined_funcs


class TableFunctionOperation(Operation):
    def __init__(self, spec):
        super(TableFunctionOperation, self).__init__(spec)

    def generate_func(self, serialized_fn):
        """
        Generates a lambda function based on udtfs.
        :param serialized_fn: serialized function which contains the proto representation of
                              the Python :class:`TableFunction`
        :return: the generated lambda function
        """
        table_function, variable_dict, user_defined_funcs = \
            operation_utils.extract_user_defined_function(serialized_fn.udfs[0])
        generate_func = eval('lambda value: %s' % table_function, variable_dict)
        return generate_func, user_defined_funcs


class PandasAggregateFunctionOperation(Operation):
    def __init__(self, spec):
        super(PandasAggregateFunctionOperation, self).__init__(spec)

    def generate_func(self, serialized_fn):
        pandas_functions, variable_dict, user_defined_funcs = reduce(
            lambda x, y: (
                ','.join([x[0], y[0]]),
                dict(chain(x[1].items(), y[1].items())),
                x[2] + y[2]),
            [operation_utils.extract_user_defined_function(udf, True)
             for udf in serialized_fn.udfs])
        variable_dict['wrap_pandas_result'] = operation_utils.wrap_pandas_result
        generate_func = eval('lambda value: wrap_pandas_result([%s])' %
                             pandas_functions, variable_dict)
        return generate_func, user_defined_funcs


class PandasBatchOverWindowAggregateFunctionOperation(Operation):
    def __init__(self, spec):
        super(PandasBatchOverWindowAggregateFunctionOperation, self).__init__(spec)
        self.windows = [window for window in self.spec.serialized_fn.windows]
        # the index among all the bounded range over window
        self.bounded_range_window_index = [-1 for _ in range(len(self.windows))]
        # Whether the specified position window is a bounded range window.
        self.is_bounded_range_window = []
        window_types = flink_fn_execution_pb2.OverWindow

        bounded_range_window_nums = 0
        for i, window in enumerate(self.windows):
            window_type = window.window_type
            if (window_type is window_types.RANGE_UNBOUNDED_PRECEDING) or (
                    window_type is window_types.RANGE_UNBOUNDED_FOLLOWING) or (
                    window_type is window_types.RANGE_SLIDING):
                self.bounded_range_window_index[i] = bounded_range_window_nums
                self.is_bounded_range_window.append(True)
                bounded_range_window_nums += 1
            else:
                self.is_bounded_range_window.append(False)

    def generate_func(self, serialized_fn):
        user_defined_funcs = []
        self.window_indexes = []
        self.mapper = []
        for udf in serialized_fn.udfs:
            pandas_agg_function, variable_dict, user_defined_func, window_index = \
                operation_utils.extract_over_window_user_defined_function(udf)
            user_defined_funcs.extend(user_defined_func)
            self.window_indexes.append(window_index)
            self.mapper.append(eval('lambda value: %s' % pandas_agg_function, variable_dict))
        return self.wrapped_over_window_function, user_defined_funcs

    def wrapped_over_window_function(self, boundaries_series):
        import pandas as pd
        OverWindow = flink_fn_execution_pb2.OverWindow
        input_series = boundaries_series[-1]
        # the row number of the arrow format data
        input_cnt = len(input_series[0])
        results = []
        # loop every agg func
        for i in range(len(self.window_indexes)):
            window_index = self.window_indexes[i]
            # the over window which the agg function belongs to
            window = self.windows[window_index]
            window_type = window.window_type
            func = self.mapper[i]
            result = []
            if self.is_bounded_range_window[window_index]:
                window_boundaries = boundaries_series[
                    self.bounded_range_window_index[window_index]]
                if window_type is OverWindow.RANGE_UNBOUNDED_PRECEDING:
                    # range unbounded preceding window
                    for j in range(input_cnt):
                        end = window_boundaries[j]
                        series_slices = [s.iloc[:end] for s in input_series]
                        result.append(func(series_slices))
                elif window_type is OverWindow.RANGE_UNBOUNDED_FOLLOWING:
                    # range unbounded following window
                    for j in range(input_cnt):
                        start = window_boundaries[j]
                        series_slices = [s.iloc[start:] for s in input_series]
                        result.append(func(series_slices))
                else:
                    # range sliding window
                    for j in range(input_cnt):
                        start = window_boundaries[j * 2]
                        end = window_boundaries[j * 2 + 1]
                        series_slices = [s.iloc[start:end] for s in input_series]
                        result.append(func(series_slices))
            else:
                # unbounded range window or unbounded row window
                if (window_type is OverWindow.RANGE_UNBOUNDED) or (
                        window_type is OverWindow.ROW_UNBOUNDED):
                    series_slices = [s.iloc[:] for s in input_series]
                    func_result = func(series_slices)
                    result = [func_result for _ in range(input_cnt)]
                elif window_type is OverWindow.ROW_UNBOUNDED_PRECEDING:
                    # row unbounded preceding window
                    window_end = window.upper_boundary
                    for j in range(input_cnt):
                        end = min(j + window_end + 1, input_cnt)
                        series_slices = [s.iloc[: end] for s in input_series]
                        result.append(func(series_slices))
                elif window_type is OverWindow.ROW_UNBOUNDED_FOLLOWING:
                    # row unbounded following window
                    window_start = window.lower_boundary
                    for j in range(input_cnt):
                        start = max(j + window_start, 0)
                        series_slices = [s.iloc[start: input_cnt] for s in input_series]
                        result.append(func(series_slices))
                else:
                    # row sliding window
                    window_start = window.lower_boundary
                    window_end = window.upper_boundary
                    for j in range(input_cnt):
                        start = max(j + window_start, 0)
                        end = min(j + window_end + 1, input_cnt)
                        series_slices = [s.iloc[start: end] for s in input_series]
                        result.append(func(series_slices))
            results.append(pd.Series(result))
        return results


class StatefulFunctionOperation(Operation):

    def __init__(self, spec, keyed_state_backend):
        self.keyed_state_backend = keyed_state_backend
        super(StatefulFunctionOperation, self).__init__(spec)

    def finish(self):
        super().finish()
        if self.keyed_state_backend:
            self.keyed_state_backend.commit()


NORMAL_RECORD = 0
TRIGGER_TIMER = 1
REGISTER_EVENT_TIMER = 0
REGISTER_PROCESSING_TIMER = 1


class AbstractStreamGroupAggregateOperation(StatefulFunctionOperation):

    def __init__(self, spec, keyed_state_backend):
        self.generate_update_before = spec.serialized_fn.generate_update_before
        self.grouping = [i for i in spec.serialized_fn.grouping]
        self.group_agg_function = None
        # If the upstream generates retract message, we need to add an additional count1() agg
        # to track current accumulated messages count. If all the messages are retracted, we need
        # to send a DELETE message to downstream.
        self.index_of_count_star = spec.serialized_fn.index_of_count_star
        self.count_star_inserted = spec.serialized_fn.count_star_inserted
        self.state_cache_size = spec.serialized_fn.state_cache_size
        self.state_cleaning_enabled = spec.serialized_fn.state_cleaning_enabled
        self.data_view_specs = extract_data_view_specs(spec.serialized_fn.udfs)
        super(AbstractStreamGroupAggregateOperation, self).__init__(spec, keyed_state_backend)

    def open(self):
        self.group_agg_function.open(FunctionContext(self.base_metric_group))

    def close(self):
        self.group_agg_function.close()

    def generate_func(self, serialized_fn):
        user_defined_aggs = []
        input_extractors = []
        filter_args = []
        # stores the indexes of the distinct views which the agg functions used
        distinct_indexes = []
        # stores the indexes of the functions which share the same distinct view
        # and the filter args of them
        distinct_info_dict = {}
        for i in range(len(serialized_fn.udfs)):
            user_defined_agg, input_extractor, filter_arg, distinct_index = \
                extract_user_defined_aggregate_function(
                    i, serialized_fn.udfs[i], distinct_info_dict)
            user_defined_aggs.append(user_defined_agg)
            input_extractors.append(input_extractor)
            filter_args.append(filter_arg)
            distinct_indexes.append(distinct_index)
        distinct_view_descriptors = {}
        for agg_index_list, filter_arg_list in distinct_info_dict.values():
            if -1 in filter_arg_list:
                # If there is a non-filter call, we don't need to check filter or not before
                # writing the distinct data view.
                filter_arg_list = []
            # use the agg index of the first function as the key of shared distinct view
            distinct_view_descriptors[agg_index_list[0]] = DistinctViewDescriptor(
                input_extractors[agg_index_list[0]], filter_arg_list)

        key_selector = RowKeySelector(self.grouping)
        if len(self.data_view_specs) > 0:
            state_value_coder = DataViewFilterCoder(self.data_view_specs)
        else:
            state_value_coder = PickleCoder()

        self.group_agg_function = self.create_process_function(
            user_defined_aggs, input_extractors, filter_args, distinct_indexes,
            distinct_view_descriptors, key_selector, state_value_coder)

        return self.process_element_or_timer, []

    def process_element_or_timer(self, input_datas: List[Tuple[int, Row, int, Row]]):
        # the structure of the input data:
        # [element_type, element(for process_element), timestamp(for timer), key(for timer)]
        # all the fields are nullable except the "element_type"
        for input_data in input_datas:
            if input_data[0] == NORMAL_RECORD:
                self.group_agg_function.process_element(input_data[1])
            else:
                self.group_agg_function.on_timer(input_data[3])
        return self.group_agg_function.finish_bundle()

    @abc.abstractmethod
    def create_process_function(self, user_defined_aggs, input_extractors, filter_args,
                                distinct_indexes, distinct_view_descriptors, key_selector,
                                state_value_coder):
        pass


class StreamGroupAggregateOperation(AbstractStreamGroupAggregateOperation):

    def __init__(self, spec, keyed_state_backend):
        super(StreamGroupAggregateOperation, self).__init__(spec, keyed_state_backend)

    def create_process_function(self, user_defined_aggs, input_extractors, filter_args,
                                distinct_indexes, distinct_view_descriptors, key_selector,
                                state_value_coder):
        aggs_handler_function = SimpleAggsHandleFunction(
            user_defined_aggs,
            input_extractors,
            self.index_of_count_star,
            self.count_star_inserted,
            self.data_view_specs,
            filter_args,
            distinct_indexes,
            distinct_view_descriptors)

        return GroupAggFunction(
            aggs_handler_function,
            key_selector,
            self.keyed_state_backend,
            state_value_coder,
            self.generate_update_before,
            self.state_cleaning_enabled,
            self.index_of_count_star)


class StreamGroupTableAggregateOperation(AbstractStreamGroupAggregateOperation):
    def __init__(self, spec, keyed_state_backend):
        super(StreamGroupTableAggregateOperation, self).__init__(spec, keyed_state_backend)

    def create_process_function(self, user_defined_aggs, input_extractors, filter_args,
                                distinct_indexes, distinct_view_descriptors, key_selector,
                                state_value_coder):
        aggs_handler_function = SimpleTableAggsHandleFunction(
            user_defined_aggs,
            input_extractors,
            self.data_view_specs,
            filter_args,
            distinct_indexes,
            distinct_view_descriptors)
        return GroupTableAggFunction(
            aggs_handler_function,
            key_selector,
            self.keyed_state_backend,
            state_value_coder,
            self.generate_update_before,
            self.state_cleaning_enabled,
            self.index_of_count_star)


class StreamGroupWindowAggregateOperation(AbstractStreamGroupAggregateOperation):
    def __init__(self, spec, keyed_state_backend):
        self._window = spec.serialized_fn.group_window
        self._named_property_extractor = self._create_named_property_function()
        self._is_time_window = None
        super(StreamGroupWindowAggregateOperation, self).__init__(spec, keyed_state_backend)

    def create_process_function(self, user_defined_aggs, input_extractors, filter_args,
                                distinct_indexes, distinct_view_descriptors, key_selector,
                                state_value_coder):
        self._is_time_window = self._window.is_time_window
        self._namespace_coder = self.keyed_state_backend._namespace_coder_impl
        if self._window.window_type == flink_fn_execution_pb2.GroupWindow.TUMBLING_GROUP_WINDOW:
            if self._is_time_window:
                window_assigner = TumblingWindowAssigner(
                    self._window.window_size, 0, self._window.is_row_time)
            else:
                window_assigner = CountTumblingWindowAssigner(self._window.window_size)
        elif self._window.window_type == flink_fn_execution_pb2.GroupWindow.SLIDING_GROUP_WINDOW:
            raise Exception("General Python UDAF in Sliding window will be implemented in "
                            "FLINK-21629")
        else:
            raise Exception("General Python UDAF in Sessiong window will be implemented in "
                            "FLINK-21630")
        if self._is_time_window:
            if self._window.is_row_time:
                trigger = EventTimeTrigger()
            else:
                trigger = ProcessingTimeTrigger()
        else:
            trigger = CountTrigger(self._window.window_size)

        window_aggregator = SimpleNamespaceAggsHandleFunction(
            user_defined_aggs,
            input_extractors,
            self.index_of_count_star,
            self.count_star_inserted,
            self._named_property_extractor,
            self.data_view_specs,
            filter_args,
            distinct_indexes,
            distinct_view_descriptors)
        return GroupWindowAggFunction(
            self._window.allowedLateness,
            key_selector,
            self.keyed_state_backend,
            state_value_coder,
            window_assigner,
            window_aggregator,
            trigger,
            self._window.time_field_index)

    def process_element_or_timer(self, input_data: Tuple[int, Row, int, int, Row]):
        results = []
        if input_data[0] == NORMAL_RECORD:
            self.group_agg_function.process_watermark(input_data[3])
            if has_cython:
                input_row = InternalRow(input_data[1]._values, input_data[1].get_row_kind().value)
            else:
                input_row = input_data[1]
            result_datas = self.group_agg_function.process_element(input_row)
            for result_data in result_datas:
                result = [NORMAL_RECORD, result_data, None]
                results.append(result)
            timers = self.group_agg_function.get_timers()
            for timer in timers:
                timer_operand_type = timer[0]  # type: TimerOperandType
                internal_timer = timer[1]  # type: InternalTimer
                window = internal_timer.get_namespace()
                key = internal_timer.get_key()
                timestamp = internal_timer.get_timestamp()
                encoded_window = self._namespace_coder.encode_nested(window)
                timer_data = [TRIGGER_TIMER, None,
                              [timer_operand_type.value, key, timestamp, encoded_window]]
                results.append(timer_data)
        else:
            timestamp = input_data[2]
            timer_data = input_data[4]
            key = list(timer_data[1])
            timer_type = timer_data[0]
            namespace = self._namespace_coder.decode_nested(timer_data[2])
            timer = InternalTimerImpl(timestamp, key, namespace)
            if timer_type == REGISTER_EVENT_TIMER:
                result_datas = self.group_agg_function.on_event_time(timer)
            else:
                result_datas = self.group_agg_function.on_processing_time(timer)
            for result_data in result_datas:
                result = [NORMAL_RECORD, result_data, None]
                results.append(result)
        return results

    def _create_named_property_function(self):
        named_property_extractor_array = []
        for named_property in self._window.namedProperties:
            if named_property == flink_fn_execution_pb2.GroupWindow.WINDOW_START:
                named_property_extractor_array.append("value.start")
            elif named_property == flink_fn_execution_pb2.GroupWindow.WINDOW_END:
                named_property_extractor_array.append("value.end")
            elif named_property == flink_fn_execution_pb2.GroupWindow.ROW_TIME_ATTRIBUTE:
                named_property_extractor_array.append("value.end - 1")
            elif named_property == flink_fn_execution_pb2.GroupWindow.PROC_TIME_ATTRIBUTE:
                named_property_extractor_array.append("-1")
            else:
                raise Exception("Unexpected property %s" % named_property)
        named_property_extractor_str = ','.join(named_property_extractor_array)
        if named_property_extractor_str:
            return eval('lambda value: [%s]' % named_property_extractor_str)
        else:
            return None


class StreamingRuntimeContext(RuntimeContext):

    def __init__(self,
                 task_name: str,
                 task_name_with_subtasks: str,
                 number_of_parallel_subtasks: int,
                 max_number_of_parallel_subtasks: int,
                 index_of_this_subtask: int,
                 attempt_number: int,
                 job_parameters: Dict[str, str],
                 keyed_state_backend: Union[RemoteKeyedStateBackend, None]):
        self._task_name = task_name
        self._task_name_with_subtasks = task_name_with_subtasks
        self._number_of_parallel_subtasks = number_of_parallel_subtasks
        self._max_number_of_parallel_subtasks = max_number_of_parallel_subtasks
        self._index_of_this_subtask = index_of_this_subtask
        self._attempt_number = attempt_number
        self._job_parameters = job_parameters
        self._keyed_state_backend = keyed_state_backend

    def get_task_name(self) -> str:
        """
        Returns the name of the task in which the UDF runs, as assigned during plan construction.
        """
        return self._task_name

    def get_number_of_parallel_subtasks(self) -> int:
        """
        Gets the parallelism with which the parallel task runs.
        """
        return self._number_of_parallel_subtasks

    def get_max_number_of_parallel_subtasks(self) -> int:
        """
        Gets the number of max-parallelism with which the parallel task runs.
        """
        return self._max_number_of_parallel_subtasks

    def get_index_of_this_subtask(self) -> int:
        """
        Gets the number of this parallel subtask. The numbering starts from 0 and goes up to
        parallelism-1 (parallelism as returned by
        :func:`~RuntimeContext.get_number_of_parallel_subtasks`).
        """
        return self._index_of_this_subtask

    def get_attempt_number(self) -> int:
        """
        Gets the attempt number of this parallel subtask. First attempt is numbered 0.
        """
        return self._attempt_number

    def get_task_name_with_subtasks(self) -> str:
        """
        Returns the name of the task, appended with the subtask indicator, such as "MyTask (3/6)",
        where 3 would be (:func:`~RuntimeContext.get_index_of_this_subtask` + 1), and 6 would be
        :func:`~RuntimeContext.get_number_of_parallel_subtasks`.
        """
        return self._task_name_with_subtasks

    def get_job_parameter(self, key: str, default_value: str):
        """
        Gets the global job parameter value associated with the given key as a string.
        """
        return self._job_parameters[key] if key in self._job_parameters else default_value

    def get_state(self, state_descriptor: ValueStateDescriptor) -> ValueState:
        if self._keyed_state_backend:
            return self._keyed_state_backend.get_value_state(state_descriptor.name, PickleCoder())
        else:
            raise Exception("This state is only accessible by functions executed on a KeyedStream.")

    def get_list_state(self, state_descriptor: ListStateDescriptor) -> ListState:
        if self._keyed_state_backend:
            return self._keyed_state_backend.get_list_state(state_descriptor.name, PickleCoder())
        else:
            raise Exception("This state is only accessible by functions executed on a KeyedStream.")

    def get_map_state(self, state_descriptor: MapStateDescriptor) -> MapState:
        if self._keyed_state_backend:
            return self._keyed_state_backend.get_map_state(state_descriptor.name, PickleCoder(),
                                                           PickleCoder())
        else:
            raise Exception("This state is only accessible by functions executed on a KeyedStream.")

    def get_reducing_state(self, state_descriptor: ReducingStateDescriptor) -> ReducingState:
        if self._keyed_state_backend:
            return self._keyed_state_backend.get_reducing_state(
                state_descriptor.get_name(), PickleCoder(), state_descriptor.get_reduce_function())
        else:
            raise Exception("This state is only accessible by functions executed on a KeyedStream.")

    def get_aggregating_state(
            self, state_descriptor: AggregatingStateDescriptor) -> AggregatingState:
        if self._keyed_state_backend:
            return self._keyed_state_backend.get_aggregating_state(
                state_descriptor.get_name(), PickleCoder(), state_descriptor.get_agg_function())
        else:
            raise Exception("This state is only accessible by functions executed on a KeyedStream.")


class DataStreamStatelessFunctionOperation(Operation):

    def __init__(self, spec):
        super(DataStreamStatelessFunctionOperation, self).__init__(spec)

    def open(self):
        for user_defined_func in self.user_defined_funcs:
            if hasattr(user_defined_func, 'open'):
                runtime_context = StreamingRuntimeContext(
                    self.spec.serialized_fn.runtime_context.task_name,
                    self.spec.serialized_fn.runtime_context.task_name_with_subtasks,
                    self.spec.serialized_fn.runtime_context.number_of_parallel_subtasks,
                    self.spec.serialized_fn.runtime_context.max_number_of_parallel_subtasks,
                    self.spec.serialized_fn.runtime_context.index_of_this_subtask,
                    self.spec.serialized_fn.runtime_context.attempt_number,
                    {p.key: p.value
                     for p in self.spec.serialized_fn.runtime_context.job_parameters},
                    None
                )
                user_defined_func.open(runtime_context)

    def generate_func(self, serialized_fn):
        func, user_defined_func = operation_utils.extract_data_stream_stateless_function(
            serialized_fn)
        return func, [user_defined_func]


class ProcessFunctionOperation(DataStreamStatelessFunctionOperation):

    def __init__(self, spec):
        self.timer_service = ProcessFunctionOperation.InternalTimerService()
        self.function_context = ProcessFunctionOperation.InternalProcessFunctionContext(
            self.timer_service)
        super(ProcessFunctionOperation, self).__init__(spec)

    def generate_func(self, serialized_fn) -> tuple:
        func, proc_func = operation_utils.extract_process_function(
            serialized_fn, self.function_context)
        return func, [proc_func]

    class InternalProcessFunctionContext(ProcessFunction.Context):
        """
        Internal implementation of ProcessFunction.Context.
        """

        def __init__(self, timer_service: TimerService):
            self._timer_service = timer_service
            self._timestamp = None

        def timer_service(self):
            return self._timer_service

        def timestamp(self) -> int:
            return self._timestamp

        def set_timestamp(self, ts: int):
            self._timestamp = ts

    class InternalTimerService(TimerService):
        """
        Internal implementation of TimerService.
        """
        def __init__(self):
            self._current_watermark = None

        def current_processing_time(self) -> int:
            return int(time.time() * 1000)

        def current_watermark(self):
            return self._current_watermark

        def set_current_watermark(self, wm):
            self._current_watermark = wm

        def register_processing_time_timer(self, t: int):
            raise Exception("Register timers is only supported on a keyed stream.")

        def register_event_time_timer(self, t: int):
            raise Exception("Register timers is only supported on a keyed stream.")


class KeyedProcessFunctionOperation(StatefulFunctionOperation):

    def __init__(self, spec, keyed_state_backend):
        self._collector = KeyedProcessFunctionOperation.InternalCollector()
        internal_timer_service = KeyedProcessFunctionOperation.InternalTimerService(
            self._collector, keyed_state_backend)
        self.function_context = KeyedProcessFunctionOperation.InternalKeyedProcessFunctionContext(
            internal_timer_service)
        self.on_timer_ctx = KeyedProcessFunctionOperation\
            .InternalKeyedProcessFunctionOnTimerContext(internal_timer_service)
        super(KeyedProcessFunctionOperation, self).__init__(spec, keyed_state_backend)

    def generate_func(self, serialized_fn) -> Tuple:
        func, proc_func = operation_utils.extract_keyed_process_function(
            serialized_fn, self.function_context, self.on_timer_ctx, self._collector,
            self.keyed_state_backend)
        return func, [proc_func]

    def open(self):
        for user_defined_func in self.user_defined_funcs:
            if hasattr(user_defined_func, 'open'):
                runtime_context = StreamingRuntimeContext(
                    self.spec.serialized_fn.runtime_context.task_name,
                    self.spec.serialized_fn.runtime_context.task_name_with_subtasks,
                    self.spec.serialized_fn.runtime_context.number_of_parallel_subtasks,
                    self.spec.serialized_fn.runtime_context.max_number_of_parallel_subtasks,
                    self.spec.serialized_fn.runtime_context.index_of_this_subtask,
                    self.spec.serialized_fn.runtime_context.attempt_number,
                    {p.key: p.value for p in
                     self.spec.serialized_fn.runtime_context.job_parameters},
                    self.keyed_state_backend)
                user_defined_func.open(runtime_context)

    class InternalCollector(object):
        """
        Internal implementation of the Collector. It uses a buffer list to store data to be emitted.
        There will be a header flag for each data type. 0 means it is a proc time timer registering
        request, while 1 means it is an event time timer and 2 means it is a normal data. When
        registering a timer, it must take along with the corresponding key for it.
        """

        def __init__(self):
            self.buf = []

        def collect_reg_proc_timer(self, a: int, key: Row):
            self.buf.append(
                (TimerOperandType.REGISTER_PROC_TIMER.value,
                 a, key, None))

        def collect_reg_event_timer(self, a: int, key: Row):
            self.buf.append(
                (TimerOperandType.REGISTER_EVENT_TIMER.value,
                 a, key, None))

        def collect_del_proc_timer(self, a: int, key: Row):
            self.buf.append(
                (TimerOperandType.DELETE_PROC_TIMER.value,
                 a, key, None))

        def collect_del_event_timer(self, a: int, key: Row):
            self.buf.append(
                (TimerOperandType.DELETE_EVENT_TIMER.value,
                 a, key, None))

        def collect(self, a: Any):
            self.buf.append((None, a))

        def clear(self):
            self.buf.clear()

    class InternalKeyedProcessFunctionOnTimerContext(
            KeyedProcessFunction.OnTimerContext, KeyedCoProcessFunction.OnTimerContext):
        """
        Internal implementation of ProcessFunction.OnTimerContext.
        """

        def __init__(self, timer_service: TimerService):
            self._timer_service = timer_service
            self._time_domain = None
            self._timestamp = None
            self._current_key = None

        def get_current_key(self):
            return self._current_key

        def set_current_key(self, current_key):
            self._current_key = current_key

        def timer_service(self) -> TimerService:
            return self._timer_service

        def timestamp(self) -> int:
            return self._timestamp

        def set_timestamp(self, ts: int):
            self._timestamp = ts

        def time_domain(self) -> TimeDomain:
            return self._time_domain

        def set_time_domain(self, td: TimeDomain):
            self._time_domain = td

    class InternalKeyedProcessFunctionContext(
            KeyedProcessFunction.Context, KeyedCoProcessFunction.Context):
        """
        Internal implementation of KeyedProcessFunction.Context.
        """

        def __init__(self, timer_service: TimerService):
            self._timer_service = timer_service
            self._timestamp = None
            self._current_key = None

        def get_current_key(self):
            return self._current_key

        def set_current_key(self, current_key):
            self._current_key = current_key

        def timer_service(self) -> TimerService:
            return self._timer_service

        def timestamp(self) -> int:
            return self._timestamp

        def set_timestamp(self, ts: int):
            self._timestamp = ts

    class InternalTimerService(TimerService):
        """
        Internal implementation of TimerService.
        """

        def __init__(self, collector, keyed_state_backend):
            self._collector = collector
            self._keyed_state_backend = keyed_state_backend
            self._current_watermark = None

        def current_processing_time(self) -> int:
            return int(time.time() * 1000)

        def current_watermark(self) -> int:
            return self._current_watermark

        def set_current_watermark(self, wm):
            self._current_watermark = wm

        def register_processing_time_timer(self, t: int):
            current_key = self._keyed_state_backend.get_current_key()
            self._collector.collect_reg_proc_timer(t, current_key)

        def register_event_time_timer(self, t: int):
            current_key = self._keyed_state_backend.get_current_key()
            self._collector.collect_reg_event_timer(t, current_key)

        def delete_processing_time_timer(self, t: int):
            current_key = self._keyed_state_backend.get_current_key()
            self._collector.collect_del_proc_timer(t, current_key)

        def delete_event_time_timer(self, t: int):
            current_key = self._keyed_state_backend.get_current_key()
            self._collector.collect_del_event_timer(t, current_key)
