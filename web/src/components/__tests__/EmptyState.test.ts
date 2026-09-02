// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import EmptyState from '../EmptyState.vue'

describe('EmptyState', () => {
  it('renders the title and hides the message/action when not provided', () => {
    const wrapper = mount(EmptyState, { props: { title: 'No items yet' } })
    expect(wrapper.text()).toContain('No items yet')
    expect(wrapper.find('.message').exists()).toBe(false)
    expect(wrapper.find('.action').exists()).toBe(false)
  })

  it('renders an optional message', () => {
    const wrapper = mount(EmptyState, {
      props: { title: 'No items yet', message: 'Add your first one.' },
    })
    expect(wrapper.find('.message').text()).toBe('Add your first one.')
  })

  it('renders an action button and emits "action" when clicked, never navigating on its own', async () => {
    const wrapper = mount(EmptyState, {
      props: { title: 'No items yet', actionLabel: '+ New item' },
    })
    const button = wrapper.find('.action')
    expect(button.exists()).toBe(true)
    expect(button.text()).toBe('+ New item')

    await button.trigger('click')

    expect(wrapper.emitted('action')).toHaveLength(1)
  })
})
