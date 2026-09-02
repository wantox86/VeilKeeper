// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import LoadingState from '../LoadingState.vue'

describe('LoadingState', () => {
  it('defaults to a "Loading…" label exposed via role="status" for screen readers', () => {
    const wrapper = mount(LoadingState)
    const root = wrapper.find('[role="status"]')
    expect(root.exists()).toBe(true)
    expect(root.attributes('aria-label')).toBe('Loading…')
    expect(wrapper.text()).toContain('Loading…')
  })

  it('accepts a custom label', () => {
    const wrapper = mount(LoadingState, { props: { label: 'Decrypting…' } })
    expect(wrapper.text()).toContain('Decrypting…')
    expect(wrapper.find('[role="status"]').attributes('aria-label')).toBe('Decrypting…')
  })

  it('applies the full-height class only when the fullHeight prop is set', () => {
    const compact = mount(LoadingState)
    expect(compact.find('.loading-state').classes()).not.toContain('full-height')

    const full = mount(LoadingState, { props: { fullHeight: true } })
    expect(full.find('.loading-state').classes()).toContain('full-height')
  })
})
