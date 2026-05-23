import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { AppComponent, SEARCH_DEBOUNCE_MS } from './app.component';

const QUERY_URL = '/api/queries/list-service-requests';
const SEARCH_URL = '/api/queries/search-service-requests';
const COMMAND_URL = '/api/commands/submit-service-request';

const stubRequest = {
  request_id: 'a1b2c3d4-0000-0000-0000-000000000001',
  submitted_by: 'demo-user',
  title: 'Broken printer',
  description: 'Printer on 3rd floor is jammed',
  status: 'submitted',
  created_at: '2026-01-01T00:00:00Z',
  updated_at: '2026-01-01T00:00:00Z',
};

describe('AppComponent', () => {
  let fixture: ComponentFixture<AppComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SEARCH_DEBOUNCE_MS, useValue: 0 },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AppComponent);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('shows loading while initial list request is in flight', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    httpMock.expectOne(QUERY_URL);
    expect(fixture.nativeElement.textContent).toContain('Loading');
  });

  it('shows empty state when list returns no requests', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    httpMock.expectOne(QUERY_URL).flush({ requests: [] });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('No requests yet');
  });

  it('shows submitted requests when list returns data', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    httpMock.expectOne(QUERY_URL).flush({ requests: [stubRequest] });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Broken printer');
    expect(fixture.nativeElement.textContent).toContain('Printer on 3rd floor is jammed');
    expect(fixture.nativeElement.textContent).toContain('submitted');
  });

  it('searches requests after the search query changes', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    httpMock.expectOne(QUERY_URL).flush({ requests: [] });
    await fixture.whenStable();
    fixture.detectChanges();

    fixture.componentInstance.setSearchQuery('printer');
    await fixture.whenStable();
    const req = httpMock.expectOne(SEARCH_URL);
    expect(req.request.body).toEqual({ query: 'printer' });
    req.flush({ requests: [stubRequest] });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Broken printer');
  });

  it('returns to the full list when the search query is cleared', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    httpMock.expectOne(QUERY_URL).flush({ requests: [] });
    await fixture.whenStable();

    fixture.componentInstance.setSearchQuery('printer');
    await fixture.whenStable();
    httpMock.expectOne(SEARCH_URL).flush({ requests: [stubRequest] });
    await fixture.whenStable();

    fixture.componentInstance.setSearchQuery('   ');
    await fixture.whenStable();
    httpMock.expectOne(QUERY_URL).flush({ requests: [stubRequest] });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Broken printer');
  });

  it('shows search empty state when no matching requests are found', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    httpMock.expectOne(QUERY_URL).flush({ requests: [] });
    await fixture.whenStable();

    fixture.componentInstance.setSearchQuery('printer');
    await fixture.whenStable();
    httpMock.expectOne(SEARCH_URL).flush({ requests: [] });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No matching requests');
  });

  it('shows submitting state while command is in flight', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    httpMock.expectOne(QUERY_URL).flush({ requests: [] });
    await fixture.whenStable();

    fixture.componentInstance.submitForm.title().value.set('Fix door');
    fixture.componentInstance.submitForm.description().value.set('Door 201 is stuck');
    fixture.componentInstance.submit();
    await fixture.whenStable();

    httpMock.expectOne(COMMAND_URL);
    expect(fixture.nativeElement.textContent).toContain('Submitting');
  });

  it('shows success message and reloads list after successful submit', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    httpMock.expectOne(QUERY_URL).flush({ requests: [] });
    await fixture.whenStable();

    fixture.componentInstance.submitForm.title().value.set('Fix door');
    fixture.componentInstance.submitForm.description().value.set('Door 201 is stuck');
    fixture.componentInstance.submit();
    await fixture.whenStable();

    httpMock.expectOne(COMMAND_URL).flush({});
    await fixture.whenStable();

    httpMock.expectOne(QUERY_URL).flush({ requests: [stubRequest] });
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('Request submitted successfully');
    expect(fixture.nativeElement.textContent).toContain('Broken printer');
  });

  it('clears active search and reloads full list after successful submit', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    httpMock.expectOne(QUERY_URL).flush({ requests: [] });
    await fixture.whenStable();

    fixture.componentInstance.setSearchQuery('printer');
    await fixture.whenStable();
    httpMock.expectOne(SEARCH_URL).flush({ requests: [] });
    await fixture.whenStable();

    fixture.componentInstance.submitForm.title().value.set('Fix door');
    fixture.componentInstance.submitForm.description().value.set('Door 201 is stuck');
    fixture.componentInstance.submit();
    await fixture.whenStable();

    httpMock.expectOne(COMMAND_URL).flush({});
    await fixture.whenStable();

    expect(fixture.componentInstance.searchQuery()).toBe('');
    httpMock.expectOne(QUERY_URL).flush({ requests: [stubRequest] });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Request submitted successfully');
    expect(fixture.nativeElement.textContent).toContain('Broken printer');
  });

  it('shows error message on submit failure', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    httpMock.expectOne(QUERY_URL).flush({ requests: [] });
    await fixture.whenStable();

    fixture.componentInstance.submitForm.title().value.set('Fix door');
    fixture.componentInstance.submitForm.description().value.set('Door 201 is stuck');
    fixture.componentInstance.submit();
    await fixture.whenStable();

    httpMock.expectOne(COMMAND_URL).error(new ProgressEvent('error'));
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('Submission failed');
  });

  it('shows error state when list request fails', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    httpMock.expectOne(QUERY_URL).error(new ProgressEvent('error'));
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Could not load requests');
  });

  it('does not submit when title is blank', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    httpMock.expectOne(QUERY_URL).flush({ requests: [] });
    await fixture.whenStable();

    fixture.componentInstance.submitForm.description().value.set('Door 201 is stuck');
    fixture.componentInstance.submit();
    await fixture.whenStable();

    httpMock.expectNone(COMMAND_URL);
  });

  it('does not submit when description is blank', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    httpMock.expectOne(QUERY_URL).flush({ requests: [] });
    await fixture.whenStable();

    fixture.componentInstance.submitForm.title().value.set('Fix door');
    fixture.componentInstance.submit();
    await fixture.whenStable();

    httpMock.expectNone(COMMAND_URL);
  });

  it('clears success message after 3 seconds', async () => {
    // jest.useFakeTimers fakes ALL setTimeout calls, including Angular's internal
    // 0ms zoneless scheduler, which deadlocks fixture.whenStable(). Instead, capture
    // only the 3-second auto-clear callback via a targeted spy so Angular's scheduler
    // continues to use real setTimeout.
    let successClearCallback: (() => void) | undefined;
    const origSetTimeout = window.setTimeout.bind(window);
    jest.spyOn(window, 'setTimeout').mockImplementation((handler, timeout, ...args: unknown[]) => {
      if (timeout === 3000 && typeof handler === 'function') {
        successClearCallback = () => handler(...args);
        return 0;
      }
      return origSetTimeout(handler, timeout, ...args);
    });
    try {
      fixture.detectChanges();
      await fixture.whenStable();
      httpMock.expectOne(QUERY_URL).flush({ requests: [] });
      await fixture.whenStable();

      fixture.componentInstance.submitForm.title().value.set('Fix door');
      fixture.componentInstance.submitForm.description().value.set('Door 201 is stuck');
      fixture.componentInstance.submit();
      await fixture.whenStable();

      httpMock.expectOne(COMMAND_URL).flush({});
      await fixture.whenStable();

      httpMock.expectOne(QUERY_URL).flush({ requests: [] });
      await fixture.whenStable();
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain('Request submitted successfully');
      expect(fixture.componentInstance.submitState()).toBe('success');

      successClearCallback!();

      expect(fixture.componentInstance.submitState()).toBe('idle');
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).not.toContain('Request submitted successfully');
    } finally {
      jest.restoreAllMocks();
    }
  });
});
