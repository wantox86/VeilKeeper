// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ErrorState from '../ErrorState.vue'

describe('ErrorState', () => {
  it('renders the message with role="alert" and no retry button by default', () => {
    const wrapper = mount(ErrorState, { props: { message: 'Failed to load this item.' } })
    expect(wrapper.attributes('role')).toBe('alert')
    expect(wrapper.text()).toContain('Failed to load this item.')
    expect(wrapper.find('.retry').exists()).toBe(false)
  })

  it('renders a retry button and emits "retry" when clicked, using a default label', async () => {
    const wrapper = mount(ErrorState, {
      props: { message: 'Network error.', showRetry: true },
    })
    const button = wrapper.find('.retry')
    expect(button.exists()).toBe(true)
    expect(button.text()).toBe('Retry')

    await button.trigger('click')

    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('accepts a custom retry label', () => {
    const wrapper = mount(ErrorState, {
      props: { message: 'Network error.', showRetry: true, retryLabel: 'Try again' },
    })
    expect(wrapper.find('.retry').text()).toBe('Try again')
  })
})
