import { handleActions } from 'redux-actions'
import { actions } from '../actions/subscriber.actions';

const defaultState = {};

export const subscriber = handleActions(
  {
    [actions.subscriberByEmailRequest]: (state, action) => ({
      loading: true
    }),
    [actions.subscriberByEmailSuccess]: (state, action) => ({
      ...action.payload
    }),
    [actions.subscriberByEmailFailure]: (state, action) => ({
      ...action.payload
    })
  },
  defaultState
);

export const bundles = handleActions(
  {
    [actions.bundlesRequest]: (state, action) => ({
      loading: true
    }),
    [actions.bundlesSuccess]: (state, action) => action.payload,
    [actions.bundlesFailure]: (state, action) => ({
      ...action.payload
    })
  },
  defaultState
);

export const paymentHistory = handleActions(
  {
    [actions.paymentHistoryRequest]: (state, action) => ({
      loading: true
    }),
    [actions.paymentHistorySuccess]: (state, action) => action.payload,
    [actions.paymentHistoryFailure]: (state, action) => ({
      ...action.payload
    })
  },
  defaultState
);