/// <reference types="jest" />
import { fakeAsync, TestBed, tick } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { By } from '@angular/platform-browser';
import { ComponentFixture } from '@angular/core/testing';
import { AppComponent, SEARCH_DEBOUNCE_MS } from './app.component';

const LIST_URL = '/api/queries/list-service-requests';
const SUBMIT_URL = '/api/commands/submit-service-request';
const SEARCH_URL = '/api/queries/search-service-requests';

function makeRequest(overrides: Record<string, string> = {}) {
  return {
    request_id: 'r1',
    title: 'Broken printer',
    description: 'Paper jam on floor 2',
    status: 'submitted',
    submitted_by: 'alice',
    created_at: '2024-01-01T00:00:00Z',
    updated_at: '2024-01-01T00:00:00Z',
    ...overrides,
  };
}

describe('AppComponent', () => {
  let fixture: ComponentFixture<AppComponent>;
  let component: AppComponent;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SEARCH_DEBOUNCE_MS, useValue: 0 },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AppComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function initWithList(requests: unknown[] = []): void {
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === LIST_URL).flush({ requests });
    fixture.detectChanges();
  }

  function fillField(selector: string, value: string): void {
    const el: HTMLInputElement | HTMLTextAreaElement = fixture.debugElement.query(
      By.css(selector),
    ).nativeElement;
    el.value = value;
    el.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function text(): string {
    return fixture.nativeElement.textContent as string;
  }

  // AC-03-03
  it('shows loading while the list request is in flight', fakeAsync(() => {
    fixture.detectChanges();
    expect(text()).toContain('Loading');
    httpMock.expectOne((r) => r.url === LIST_URL).flush({ requests: [] });
    fixture.detectChanges();
  }));

  // AC-03-01
  it('shows no requests yet when list returns empty', fakeAsync(() => {
    initWithList([]);
    expect(text()).toContain('No service requests yet.');
  }));

  // AC-03-02
  it('shows title, description, and status for each request', fakeAsync(() => {
    initWithList([makeRequest()]);
    expect(text()).toContain('Broken printer');
    expect(text()).toContain('Paper jam on floor 2');
    expect(text()).toContain('submitted');
  }));

  // AC-03-04
  it('shows error message when list request fails', fakeAsync(() => {
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === LIST_URL).error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(text()).toContain('Could not load requests');
  }));

  // AC-02-07
  it('does not call submit endpoint when title is blank', fakeAsync(() => {
    initWithList();
    fillField('#description', 'Some description');
    component.submit();
    httpMock.expectNone(SUBMIT_URL);
  }));

  // AC-02-08
  it('does not call submit endpoint when description is blank', fakeAsync(() => {
    initWithList();
    fillField('#title', 'Some title');
    component.submit();
    httpMock.expectNone(SUBMIT_URL);
  }));

  // AC-02-02
  it('shows submitting indicator and disables button while request is in flight', fakeAsync(() => {
    initWithList();
    fillField('#title', 'A title');
    fillField('#description', 'A description');
    component.submit();
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.debugElement.query(
      By.css('button[type="submit"]'),
    ).nativeElement;
    expect(button.disabled).toBe(true);
    expect(text()).toContain('Submitting');

    httpMock.expectOne(SUBMIT_URL).flush({});
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === LIST_URL).flush({ requests: [] });
    fixture.detectChanges();
    tick(3000);
  }));

  // AC-02-01
  it('shows success message after a successful submit', fakeAsync(() => {
    initWithList();
    fillField('#title', 'A title');
    fillField('#description', 'A description');
    component.submit();

    httpMock.expectOne(SUBMIT_URL).flush({});
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === LIST_URL).flush({ requests: [] });
    fixture.detectChanges();

    expect(text()).toContain('Service request submitted successfully');
    tick(3000);
  }));

  // AC-02-03
  it('removes success message after 3 seconds', fakeAsync(() => {
    initWithList();
    fillField('#title', 'A title');
    fillField('#description', 'A description');
    component.submit();

    httpMock.expectOne(SUBMIT_URL).flush({});
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === LIST_URL).flush({ requests: [] });
    fixture.detectChanges();

    expect(text()).toContain('Service request submitted successfully');
    tick(3000);
    fixture.detectChanges();
    expect(text()).not.toContain('Service request submitted successfully');
  }));

  // AC-02-04
  it('shows submission failed when the submit request errors', fakeAsync(() => {
    initWithList();
    fillField('#title', 'A title');
    fillField('#description', 'A description');
    component.submit();
    fixture.detectChanges();

    httpMock.expectOne(SUBMIT_URL).error(new ProgressEvent('error'));
    fixture.detectChanges();

    expect(text()).toContain('Submission failed');
  }));

  // AC-02-05
  it('calls list endpoint after successful submit and shows new request', fakeAsync(() => {
    initWithList();
    fillField('#title', 'New request');
    fillField('#description', 'Details here');
    component.submit();

    httpMock.expectOne(SUBMIT_URL).flush({});
    fixture.detectChanges();
    httpMock
      .expectOne((r) => r.url === LIST_URL)
      .flush({ requests: [makeRequest({ title: 'New request' })] });
    fixture.detectChanges();

    expect(text()).toContain('New request');
    tick(3000);
  }));

  // AC-02-06
  it('clears search and calls list endpoint after submit when search is active', fakeAsync(() => {
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === LIST_URL).flush({ requests: [] });

    component.setSearchQuery('printer');
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === SEARCH_URL).flush({ requests: [] });

    fillField('#title', 'A title');
    fillField('#description', 'A description');
    component.submit();

    httpMock.expectOne(SUBMIT_URL).flush({});
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === LIST_URL).flush({ requests: [] });
    fixture.detectChanges();

    expect(component.searchQuery()).toBe('');
    tick(3000);
  }));

  // AC-08-02
  it('calls list endpoint when search field is blank', fakeAsync(() => {
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === LIST_URL).flush({ requests: [] });
    fixture.detectChanges();
    // Only the list URL is expected — not the search URL
  }));

  // AC-08-03
  it('calls list endpoint when search is cleared to blank', fakeAsync(() => {
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === LIST_URL).flush({ requests: [] });

    component.setSearchQuery('printer');
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === SEARCH_URL).flush({ requests: [] });

    component.setSearchQuery('');
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === LIST_URL).flush({ requests: [] });
    fixture.detectChanges();
  }));

  // AC-08-01
  it('shows only matching requests when search term is entered', fakeAsync(() => {
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === LIST_URL).flush({ requests: [] });

    component.setSearchQuery('printer');
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === SEARCH_URL).flush({ requests: [makeRequest()] });
    fixture.detectChanges();

    expect(text()).toContain('Broken printer');
  }));

  // AC-08-04
  it('shows no matching requests when search returns empty', fakeAsync(() => {
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === LIST_URL).flush({ requests: [] });

    component.setSearchQuery('nomatches');
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === SEARCH_URL).flush({ requests: [] });
    fixture.detectChanges();

    expect(text()).toContain('No matching service requests.');
  }));
});
